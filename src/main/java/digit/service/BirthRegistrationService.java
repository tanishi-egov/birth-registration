package digit.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import digit.enrichment.BirthApplicationEnrichment;
import digit.kafka.Producer;
import digit.repository.BirthRegistrationRepository;
import digit.validators.BirthApplicationValidator;
import digit.web.models.*;
import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.common.contract.workflow.ProcessInstance;
import org.egov.common.contract.workflow.ProcessInstanceRequest;
import org.egov.common.contract.workflow.State;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class BirthRegistrationService {

    @Autowired
    private BirthApplicationValidator validator;

    @Autowired
    private BirthApplicationEnrichment enrichmentUtil;

    @Autowired
    private UserService userService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private BirthRegistrationRepository birthRegistrationRepository;

    @Autowired
    private Producer producer;

    private ObjectMapper mapper;

    public List<BirthRegistrationApplication> registerBtRequest(BirthRegistrationRequest birthRegistrationRequest) {
        // Validate applications
        validator.validateBirthApplication(birthRegistrationRequest);

        // Enrich applications
        enrichmentUtil.enrichBirthApplication(birthRegistrationRequest);

        System.out.println("birthRegistrationRequest");
        System.out.println(birthRegistrationRequest);
//         Enrich/Upsert user in upon birth registration
        userService.callUserService(birthRegistrationRequest);
//
        // Initiate workflow for the new application
        workflowService.updateWorkflowStatus(birthRegistrationRequest);

        // Push the application to the topic for persister to listen and persist
        producer.push("save-bt-application", birthRegistrationRequest);

        // Return the response back to user
        return birthRegistrationRequest.getBirthRegistrationApplications();
    }

    public List<BirthRegistrationApplication> searchBtApplications(RequestInfo requestInfo, BirthApplicationSearchCriteria birthApplicationSearchCriteria) {
        // Fetch applications from database according to the given search criteria
        List<BirthRegistrationApplication> applications = birthRegistrationRepository.getApplications(birthApplicationSearchCriteria);
//        log.info(applications.toString());
        // If no applications are found matching the given criteria, return an empty list
        if(CollectionUtils.isEmpty(applications))
            return new ArrayList<>();

        // Enrich mother and father of applicant objects
        applications.forEach(application -> {
            enrichmentUtil.enrichFatherApplicantOnSearch(application);
            enrichmentUtil.enrichMotherApplicantOnSearch(application);

            ProcessInstance instance = workflowService.getProcessInstance(requestInfo, application.getTenantId(), application.getApplicationNumber());
            log.info("current instance:" + instance.toString());

            Workflow workflow = new Workflow();
            workflow.setStatus(instance.getState().getState());
            workflow.setComment(instance.getComment());
            workflow.setAction(instance.getAction());

            application.setWorkflow(workflow);
        });
        // Otherwise return the found applications
        return applications;
    }

    public BirthRegistrationApplication updateBtApplication(BirthRegistrationRequest birthRegistrationRequest) {
        // Validate whether the application that is being requested for update indeed exists
        BirthRegistrationApplication existingApplication = validator.validateApplicationExistence(birthRegistrationRequest.getBirthRegistrationApplications().get(0));

        if(existingApplication == null) throw new CustomException("EG_BT_APP_ERR", "the given application does not exist");

        // Enrich application upon update
        enrichmentUtil.enrichBirthApplicationUponUpdate(birthRegistrationRequest);
        if(!birthRegistrationRequest.getBirthRegistrationApplications().get(0).getWorkflow().getAction().isEmpty()) {
            workflowService.updateWorkflowStatus(birthRegistrationRequest);
        }
//        log.info(existingApplication.toString());
//        log.info("workflow worked");
        // Just like create request, update request will be handled asynchronously by the persister
        producer.push("update-bt-application", birthRegistrationRequest);

        return birthRegistrationRequest.getBirthRegistrationApplications().get(0);


    }
}