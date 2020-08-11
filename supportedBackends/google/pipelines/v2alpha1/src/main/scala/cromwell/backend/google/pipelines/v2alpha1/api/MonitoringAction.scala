package cromwell.backend.google.pipelines.v2alpha1.api

import com.google.api.services.genomics.v2alpha1.model.{Action, Mount}
import cromwell.backend.google.pipelines.common.PipelinesApiConfigurationAttributes.GcsTransferConfiguration
import cromwell.backend.google.pipelines.common.WorkflowOptionKeys
import cromwell.backend.google.pipelines.common.api.PipelinesApiRequestFactory.CreatePipelineParameters
import cromwell.backend.google.pipelines.v2alpha1.api.ActionBuilder.Labels.{Key, Value}
import cromwell.backend.google.pipelines.v2alpha1.api.ActionBuilder.cloudSdkShellAction
import cromwell.backend.google.pipelines.v2alpha1.api.ActionCommands.localizeFile
import cromwell.core.path.{Path, PathFactory}

import scala.collection.JavaConverters._

trait MonitoringAction {
  object Env {
    /**
      * Name of an environmental variable
      */
    val WorkflowId = "WORKFLOW_ID"
    val TaskCallName = "TASK_CALL_NAME"
    val TaskCallIndex = "TASK_CALL_INDEX"
    val TaskCallAttempt = "TASK_CALL_ATTEMPT"
    val DiskMounts = "DISK_MOUNTS"
  }

  private def monitoringAction(createPipelineParameters: CreatePipelineParameters, image: String, mounts: List[Mount]): List[Action] = {
    val job = createPipelineParameters.jobDescriptor

    val environment = Map(
      Env.WorkflowId -> job.workflowDescriptor.id.toString,
      Env.TaskCallName -> job.taskCall.localName,
      Env.TaskCallIndex -> (job.key.index map { _.toString } getOrElse "NA"),
      Env.TaskCallAttempt -> job.key.attempt.toString,
      Env.DiskMounts -> mounts.map(_.getPath).mkString(" "),
    )

    val monitoringAction = ActionBuilder.monitoringAction(image, environment, mounts)

    val describeAction = ActionBuilder.describeDocker("monitoring action", monitoringAction)
      .setFlags(List(ActionFlag.RunInBackground.toString).asJava)

    val terminationAction = ActionBuilder.monitoringTerminationAction()

    List(describeAction, monitoringAction, terminationAction)
  }

  def monitoringActions(createPipelineParameters: CreatePipelineParameters, mounts: List[Mount]): List[Action] = {
    val workflowOptions = createPipelineParameters.jobDescriptor.workflowDescriptor.workflowOptions

    workflowOptions.get(WorkflowOptionKeys.MonitoringImage).toOption match {
      case Some(image) => monitoringAction(createPipelineParameters, image, mounts)
      case None => List.empty
    }
  }

  def monitoringLocalizationActions(createPipelineParameters: CreatePipelineParameters, mounts: List[Mount])(implicit gcsTransferConfiguration: GcsTransferConfiguration): List[Action] = {
    val workflowOptions = createPipelineParameters.jobDescriptor.workflowDescriptor.workflowOptions
    val monitoringImageScriptOption = for {
      _ <- workflowOptions.get(WorkflowOptionKeys.MonitoringImage).toOption
      scriptValue <- workflowOptions.get(WorkflowOptionKeys.MonitoringImageScript).toOption
    } yield scriptValue

    // TODO: un-hard-code name of script
    monitoringImageScriptOption match {
      case Some(imageScript) =>
        val cloudLocation: Path = PathFactory.buildPath(
          imageScript,
          createPipelineParameters.jobPaths.workflowPaths.pathBuilders,
        )
        val localLocation: Path = createPipelineParameters.commandScriptContainerPath.sibling("imageMonitoring.sh")
        val localizeMonitorAction = cloudSdkShellAction(
          localizeFile(cloudLocation, localLocation)
        )(mounts = mounts, labels = Map(Key.Tag -> Value.Localization))
        val describeLocalizeMonitorAction = ActionBuilder.describeDocker("localizing the image monitoring script",
          localizeMonitorAction)
        List(describeLocalizeMonitorAction, localizeMonitorAction)
      case None => List.empty
    }
  }
}
