package mesosphere.marathon.api.v2

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.{ MarathonState, Timestamp }
import mesosphere.marathon.Protos._
import scala.collection.JavaConversions._
import mesosphere.marathon.api.validation.FieldConstraints.{ FieldPattern, FieldNotEmpty }

case class ScalingStrategy(
    minimumHealthCapacity: Double,
    maximumRunningFactor: Option[Double]) {
  def toProto: ScalingStrategyDefinition = {
    val strategy = ScalingStrategyDefinition.newBuilder()
      .setMinimumHealthCapacity(minimumHealthCapacity)

    maximumRunningFactor.foreach(strategy.setMaximumRunningFactor)

    strategy.build()
  }
}

case class Group(
    @FieldNotEmpty @FieldPattern(regexp = "^[A-Za-z0-9_.-]+$") id: String,
    scalingStrategy: ScalingStrategy,
    apps: Seq[AppDefinition] = Seq.empty,
    groups: Seq[Group] = Seq.empty,
    version: Timestamp = Timestamp.now()) extends MarathonState[GroupDefinition, Group] {

  override def mergeFromProto(msg: GroupDefinition): Group = Group.fromProto(msg)

  override def mergeFromProto(bytes: Array[Byte]): Group = Group.fromProto(GroupDefinition.parseFrom(bytes))

  override def toProto: GroupDefinition = {
    GroupDefinition.newBuilder
      .setId(id)
      .setScalingStrategy(scalingStrategy.toProto)
      .setVersion(version.toString)
      .addAllApps(apps.map(_.toProto))
      .addAllGroups(groups.map(_.toProto))
      .build()
  }
}

object Group {
  def empty(): Group = Group("", ScalingStrategy(0, None))

  def fromProto(msg: GroupDefinition): Group = {
    val scalingStrategy = msg.getScalingStrategy
    val maximumRunningFactor = {
      if (scalingStrategy.hasMaximumRunningFactor) Some(msg.getScalingStrategy.getMaximumRunningFactor)
      else None
    }
    Group (
      id = msg.getId,
      scalingStrategy = ScalingStrategy(
        scalingStrategy.getMinimumHealthCapacity,
        maximumRunningFactor
      ),
      apps = msg.getAppsList.map(AppDefinition.fromProto),
      groups = msg.getGroupsList.map(fromProto),
      version = Timestamp(msg.getVersion)
    )
  }

}
