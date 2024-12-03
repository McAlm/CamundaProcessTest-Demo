package com.mcalm.demo;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import io.camunda.zeebe.client.api.search.response.SearchQueryResponse;
import io.camunda.zeebe.client.api.search.response.UserTask;

@SpringBootTest(properties = {
    "camunda.client.zeebe.defaults.enabled=false" // disable all job workers
})
@CamundaSpringProcessTest
public class ProcessTest {

  @Autowired
  private ZeebeClient client;

  @Autowired
  private CamundaProcessTestContext processTestContext;

  @Test
  void noRisk() {
    final ProcessInstanceEvent processInstance = client.newCreateInstanceCommand()//
        .bpmnProcessId("doNothing")//
        .latestVersion()//
        .variables(Map.of("age", 53))
        .send().join();

    CamundaAssert.assertThat(processInstance).hasActiveElements("send approval");

    completeJobs("sendApproval", Map.of());

    CamundaAssert.assertThat(processInstance)//
        .hasCompletedElements("send approval")//
        .isCompleted();
  }

  @Test
  void mediumRisk() throws InterruptedException {

    var applicationNumber = "12345";
    final ProcessInstanceEvent processInstance = client.newCreateInstanceCommand()//
        .bpmnProcessId("doNothing")//
        .latestVersion()//
        .variables(Map.of("age", 67, "applicationNumber", applicationNumber))
        .send().join();

    CamundaAssert.assertThat(processInstance).hasActiveElements("Decide upon application");

    processTestContext.increaseTime(Duration.ofDays(2));
    CamundaAssert.assertThat(processInstance).hasActiveElements( "Decide upon application", "Escalate to Supervisor");

    client.newPublishMessageCommand()//
        .messageName("MSG_REQUEST_DOCUMENTS")//
        .correlationKey(applicationNumber)//
        .send().join();

    CamundaAssert.assertThat(processInstance).hasActiveElements(// //
        "Escalate to Supervisor", //
        "Request further documents");

    //completeUserTask("Escalate to Supervisor", Map.of());

  }

  @Test
  void highRisk() {

    var applicationNumber = "12345";

    final ProcessInstanceEvent processInstance = client.newCreateInstanceCommand()//
        .bpmnProcessId("doNothing")//
        .latestVersion()//
        .variables(Map.of("age", 18 , "applicationNumber", applicationNumber))
        .send().join();

    CamundaAssert.assertThat(processInstance).hasActiveElements("send rejection");

    completeJobs("io.camunda:sendgrid:1", Map.of());

    CamundaAssert.assertThat(processInstance)//
        .hasCompletedElements("send rejection")//
        .isCompleted();
  }


  private void completeJobs(final String jobType, final Map<String, Object> variables) {
    client
        .newWorker()
        .jobType(jobType)
        .handler((jobClient, job) -> jobClient.newCompleteCommand(job).variables(variables).send())
        .open();
  }

  private void completeUserTask(final String taskDefinitionId, final Map<String, Object> variables) {
    SearchQueryResponse<UserTask> response = client.newUserTaskQuery().filter(c -> c.elementId("doSomethingUserTask"))
        .send().join();
    response.items()
        .forEach(task -> client.newUserTaskCompleteCommand(task.getUserTaskKey()).variables(variables).send().join());
  }
}