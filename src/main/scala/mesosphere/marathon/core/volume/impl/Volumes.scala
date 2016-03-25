package mesosphere.marathon.core.volume.impl

import com.wix.accord._
import com.wix.accord.combinators.{ Fail, NilValidator }
import com.wix.accord.dsl._
import com.wix.accord.Validator
import mesosphere.marathon.core.volume._
import mesosphere.marathon.state.Container
import mesosphere.marathon.state.{ DockerVolume, PersistentVolume, Volume }
import org.apache.mesos.Protos.{ CommandInfo, ContainerInfo, Volume => MesosVolume, Environment }
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

protected trait PersistentVolumeProvider extends VolumeProvider[PersistentVolume] {
  /**
    * don't invoke validator on v because that's circular, just check the additional
    * things that we need for agent local volumes.
    * see implicit validator in the PersistentVolume class for reference.
    */
  val validPersistentVolume: Validator[PersistentVolume]

  /** convenience validator that type-checks for persistent volume */
  val validation = new Validator[Volume] {
    val notPersistentVolume = new Fail[Volume]("is not a persistent volume")
    override def apply(v: Volume): Result = v match {
      case pv: PersistentVolume => validate(pv)(validPersistentVolume)
      case _                    => validate(v)(notPersistentVolume)
    }
  }

  /**
    * @return true if volume has a provider name that matches ours exactly
    */
  def accepts(volume: PersistentVolume): Boolean = {
    volume.persistent.providerName.isDefined && volume.persistent.providerName.get == name
  }

  override def apply(container: Option[Container]): Iterable[PersistentVolume] =
    container.fold(Seq.empty[PersistentVolume]) {
      _.volumes.collect{ case vol: PersistentVolume if accepts(vol) => vol }
    }
}

/**
  * DVDIProvider (Docker Volume Driver Interface provider) handles persistent volumes allocated
  * by a specific docker volume driver plugin. This works for both docker and mesos containerizers,
  * albeit with some limitations:
  *   - only a single volume driver per container is allowed when using the docker containerizer
  *   - docker containerizer requires that referenced volumes be created prior to application launch
  *   - mesos containerizer only supports volumes mounted in RW mode
  */
protected object DVDIProvider extends PersistentVolumeProvider with ContextUpdate {

  val name = "dvdi"

  val optionDriver = name + "/driverName"
  val optionIOPS = name + "/iops"
  val optionType = name + "/volumeType"

  val validOptions: Validator[Map[String, String]] = validator[Map[String, String]] { opt =>
    opt.get(optionDriver) as "driverName option" is notEmpty
    // TODO(jdef) stronger validation for contents of driver name
    opt.get(optionDriver).each as "driverName option" is notEmpty
    // TODO(jdef) validate contents of iops and volume type options
  }

  val validPersistentVolume = validator[PersistentVolume] { v =>
    v.persistent.name is notEmpty
    v.persistent.name.each is notEmpty
    v.persistent.providerName is notEmpty
    v.persistent.providerName.each is notEmpty
    v.persistent.providerName.each is equalTo(name) // sanity check
    v.persistent.options is notEmpty
    v.persistent.options.each is valid(validOptions)
  }

  def driversInUse(ct: Container): Set[String] =
    ct.volumes.collect{
      case pv: PersistentVolume if accepts(pv) && !pv.persistent.options.isEmpty =>
        pv.persistent.options.get.get(optionDriver)
    }.flatten.foldLeft(Set.empty[String])(_ + _)

  /** Only allow a single docker volume driver to be specified w/ the docker containerizer. */
  val containerValidation: Validator[Container] = validator[Container] { ct =>
    (ct.`type` is equalTo(ContainerInfo.Type.MESOS)) or (
      (ct.`type` is equalTo(ContainerInfo.Type.DOCKER)) and (driversInUse(ct).size should be == 1))
  }

  /** non-agent-local PersistentVolumes can be serialized into a Mesos Protobuf */
  def toMesosVolume(volume: PersistentVolume): MesosVolume =
    MesosVolume.newBuilder
      .setContainerPath(volume.containerPath)
      .setHostPath(volume.persistent.name.get) // validation should protect us from crashing here since name is req'd
      .setMode(volume.mode)
      .build

  def updatedContainer(cc: ContainerContext, pv: PersistentVolume): Option[ContainerContext] = {
    // special behavior for docker vs. mesos containers
    // - docker containerizer: serialize volumes into mesos proto
    // - docker containerizer: specify "volumeDriver" for the container
    val ci = cc.ci // TODO(jdef) clone?
    if (ci.getType == ContainerInfo.Type.DOCKER && ci.hasDocker) {
      val driverName = pv.persistent.options.get(optionDriver)
      if (ci.getDocker.getVolumeDriver != driverName) {
        ci.setDocker(ci.getDocker.toBuilder.setVolumeDriver(driverName).build)
      }
      Some(ContainerContext(ci.addVolumes(toMesosVolume(pv))))
    }
    None
  }

  def updatedCommand(cc: CommandContext, pv: PersistentVolume): Option[CommandContext] = {
    // special behavior for docker vs. mesos containers
    // - mesos containerizer: serialize volumes into envvar sets
    val (ct, ci) = (cc.ct, cc.ci) // TODO(jdef) clone ci?
    if (ct == ContainerInfo.Type.MESOS) {
      val env = if (ci.hasEnvironment) ci.getEnvironment.toBuilder else Environment.newBuilder
      val toAdd = volumeToEnv(pv, env.getVariablesList)
      env.addAllVariables(toAdd.asJava)
      Some(CommandContext(ct, ci.setEnvironment(env.build)))
    }
    None
  }

  override protected def updated[C <: BuilderContext](context: C, v: Volume): Option[C] = {
    v match {
      case pv: PersistentVolume => {
        if (accepts(pv)) context match {
          case cc: ContainerContext => updatedContainer(cc, pv).map(_.asInstanceOf[C])
          case cc: CommandContext   => updatedCommand(cc, pv).map(_.asInstanceOf[C])
        }
        else None
      }
      case _ => None
    }
  }

  val dvdiVolumeName = "DVDI_VOLUME_NAME"
  val dvdiVolumeDriver = "DVDI_VOLUME_DRIVER"
  val dvdiVolumeOpts = "DVDI_VOLUME_OPTS"

  def volumeToEnv(v: PersistentVolume, i: Iterable[Environment.Variable]): Seq[Environment.Variable] = {
    val offset = i.filter(_.getName.startsWith(dvdiVolumeName)).map{ s =>
      val ss = s.getName.substring(dvdiVolumeName.size)
      if (ss.length > 0) ss.toInt else 0
    }.foldLeft(-1)((z, i) => if (i > z) i else z)
    val suffix = if (offset >= 0) (offset + 1).toString else ""

    def newVar(name: String, value: String): Environment.Variable =
      Environment.Variable.newBuilder.setName(name).setValue(value).build

    Seq(
      newVar(dvdiVolumeName + suffix, v.persistent.name.get),
      newVar(dvdiVolumeDriver + suffix, v.persistent.options.get(optionDriver))
    // TODO(jdef) support other options here
    )
  }
}

/**
  * DockerHostVolumeProvider handles Docker volumes that a user would like to mount at
  * predetermined host and container paths. Docker host volumes are not intended to be used
  * with "non-local" docker volume drivers. If you want to use a docker volume driver then
  * use a PersistentVolume instead.
  */
protected object DockerHostVolumeProvider
    extends VolumeProvider[DockerVolume]
    with ContextUpdate {
  val name = "docker" // only because we should have a non-empty name

  /** no special case validation here, it's handled elsewhere */
  val validation: Validator[Volume] = new NilValidator[Volume]

  // no provider-specific rules at the container level
  val containerValidation: Validator[Container] = new Validator[Container] {
    def apply(x: Container) = Success
  }

  /** DockerVolumes can be serialized into a Mesos Protobuf */
  def toMesosVolume(volume: DockerVolume): MesosVolume =
    MesosVolume.newBuilder
      .setContainerPath(volume.containerPath)
      .setHostPath(volume.hostPath)
      .setMode(volume.mode)
      .build

  /** @return a possibly modified builder if `v` is a DockerVolume */
  override protected def updated[C <: BuilderContext](context: C, v: Volume): Option[C] = {
    context match {
      case cc: ContainerContext => {
        val ci = cc.ci // TODO(jdef) clone?
        Some(v).collect{ case dv: DockerVolume => ci.addVolumes(toMesosVolume(dv)) }.
          map(ContainerContext(_).asInstanceOf[C])
      }
    }
    None
  }

  override def apply(container: Option[Container]): Iterable[DockerVolume] =
    container.fold(Seq.empty[DockerVolume])(_.volumes.collect{ case vol: DockerVolume => vol })
}

/**
  * AgentVolumeProvider handles persistent volumes allocated from agent resources.
  */
protected[volume] object AgentVolumeProvider extends PersistentVolumeProvider with LocalVolumes {
  import org.apache.mesos.Protos.Volume.Mode
  import mesosphere.marathon.api.v2.Validation._

  /** this is the name of the agent volume provider */
  val name = "agent"

  // no provider-specific rules at the container level
  val containerValidation: Validator[Container] = new Validator[Container] {
    def apply(x: Container) = Success
  }

  val validPersistentVolume = validator[PersistentVolume] { v =>
    v.persistent.size is notEmpty
    v.mode is equalTo(Mode.RW)
    //persistent volumes require those CLI parameters provided
    v is configValueSet("mesos_authentication_principal", "mesos_role", "mesos_authentication_secret_file")
  }

  override def accepts(volume: PersistentVolume): Boolean = {
    volume.persistent.providerName.getOrElse(name) == name
  }
}
