package mesosphere.marathon.api.v1

import mesosphere.marathon.{ContainerInfo}
import mesosphere.marathon.state.{Timestamp, Timestamped}
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.api.validation.FieldConstraints._
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
case class AppDefinitionMin(

  @FieldNotEmpty
  @FieldPattern(regexp = "^[A-Za-z0-9_.-]+$")
  id: String = "",

  cmd: String = "",

  env: Map[String, String] = Map[String, String](),

  @FieldMin(0)
  instances: Int = AppDefinition.DEFAULT_INSTANCES,

  cpus: Double = AppDefinition.DEFAULT_CPUS,

  mem: Double = AppDefinition.DEFAULT_MEM,

  @FieldPattern(regexp="(^//cmd$)|(^/[^/].*$)|")
  executor: String = "",

  constraints: Set[Constraint] = Set[Constraint](),

  uris: Seq[String] = Seq[String](),

  @FieldUniqueElements
  ports: Seq[Int] = Seq[Int](0),

  taskRateLimit: Double = AppDefinition.DEFAULT_TASK_RATE_LIMIT,

  @FieldJsonDeserialize(contentAs = classOf[ContainerInfo])
  container: Option[ContainerInfo] = None,

  @FieldJsonProperty
  version: Timestamp = Timestamp.now

) {

  def this() = this(id = "")

}

object AppDefinition2 {
  val DEFAULT_CPUS = 1.0
  val DEFAULT_MEM = 128.0
  val DEFAULT_INSTANCES = 0
  val DEFAULT_TASK_RATE_LIMIT = 1.0
}
