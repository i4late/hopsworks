package se.kth.hopsworks.rest;


import org.apache.commons.beanutils.BeanUtils;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import se.kth.bbc.project.Project;
import se.kth.hopsworks.controller.ResponseMessages;
import se.kth.hopsworks.filters.AllowedRoles;
import se.kth.hopsworks.workflows.*;

import javax.annotation.security.RolesAllowed;
import javax.ejb.EJB;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@RequestScoped
@RolesAllowed({"SYS_ADMIN", "BBC_USER"})
@TransactionAttribute(TransactionAttributeType.NEVER)
public class WorkflowService {
    private final static Logger logger = Logger.getLogger(WorkflowService.class.
            getName());

    @EJB
    private WorkflowFacade workflowFacade;

    @EJB
    private NodeFacade nodeFacade;

    @EJB
    private NoCacheResponse noCacheResponse;

    @Inject
    private NodeService nodeService;

    @Inject
    private EdgeService edgeService;

    @Inject
    private WorkflowExecutionService workflowExecutionService;

    public void setProject(Project project) {
        this.project = project;
    }

    public Project getProject() {
        return project;
    }

    Project project;

    public WorkflowService(){

    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @AllowedRoles(roles = {AllowedRoles.DATA_SCIENTIST, AllowedRoles.DATA_OWNER})
    public Response index() throws AppException {
        List<Workflow> workflows = workflowFacade.findAll();
        return noCacheResponse.getNoCacheResponseBuilder(Response.Status.OK).entity(workflows).build();
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @AllowedRoles(roles = {AllowedRoles.DATA_SCIENTIST, AllowedRoles.DATA_OWNER})
    public Response create(
            Workflow workflow,
            @Context HttpServletRequest req) throws AppException {
        workflow.setProjectId(this.project.getId());
        workflowFacade.persist(workflow);

        JsonNode json = new ObjectMapper().valueToTree(workflow);
        return noCacheResponse.getNoCacheResponseBuilder(Response.Status.OK).entity(json.toString()).build();

    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @AllowedRoles(roles = {AllowedRoles.DATA_SCIENTIST, AllowedRoles.DATA_OWNER})
    public Response show(
            @PathParam("id") Integer id) throws AppException {
        Workflow workflow = workflowFacade.findById(id);
        if (workflow == null) {
            throw new AppException(Response.Status.BAD_REQUEST.getStatusCode(),
                    ResponseMessages.WORKFLOW_NOT_FOUND);
        }
        JsonNode json = new ObjectMapper().valueToTree(workflow);
        return noCacheResponse.getNoCacheResponseBuilder(Response.Status.OK).entity(json.toString()).build();
    }

    @PUT
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @AllowedRoles(roles = {AllowedRoles.DATA_SCIENTIST, AllowedRoles.DATA_OWNER})
    public Response update(
            String stringParams,
            @PathParam("id") Integer id) throws AppException, IllegalAccessException, InvocationTargetException {
        Workflow workflow = workflowFacade.findById(id);
        if (workflow == null) {
            throw new AppException(Response.Status.BAD_REQUEST.getStatusCode(),
                    ResponseMessages.WORKFLOW_NOT_FOUND);
        }
        Map<String, Object> paramsMap = new ObjectMapper().convertValue(stringParams, Map.class);
        BeanUtils.populate(workflow, paramsMap);
        workflow = workflowFacade.merge(workflow);
        JsonNode json = new ObjectMapper().valueToTree(workflow);
        return noCacheResponse.getNoCacheResponseBuilder(Response.Status.OK).entity(json.toString()).build();
    }

    @DELETE
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @AllowedRoles(roles = {AllowedRoles.DATA_SCIENTIST, AllowedRoles.DATA_OWNER})
    public Response delete(
            @PathParam("id") Integer id) throws AppException {
        Workflow workflow = workflowFacade.findById(id);
        if (workflow == null) {
            throw new AppException(Response.Status.BAD_REQUEST.getStatusCode(),
                    ResponseMessages.WORKFLOW_NOT_FOUND);
        }
        workflowFacade.remove(workflow);
        return noCacheResponse.getNoCacheResponseBuilder(Response.Status.OK).build();
    }

    @Path("{id}/nodes")
    @AllowedRoles(roles = {AllowedRoles.DATA_SCIENTIST, AllowedRoles.DATA_OWNER})
    public NodeService nodes(
            @PathParam("id") Integer id) throws AppException {
        Workflow workflow = workflowFacade.findById(id);
        if (workflow == null) {
            throw new AppException(Response.Status.BAD_REQUEST.getStatusCode(),
                    ResponseMessages.WORKFLOW_NOT_FOUND);
        }
        this.nodeService.setWorkflow(workflow);

        return this.nodeService;
    }

    @Path("{id}/edges")
    @AllowedRoles(roles = {AllowedRoles.DATA_SCIENTIST, AllowedRoles.DATA_OWNER})
    public EdgeService edges(
            @PathParam("id") Integer id) throws AppException {
        Workflow workflow = workflowFacade.findById(id);
        if (workflow == null) {
            throw new AppException(Response.Status.BAD_REQUEST.getStatusCode(),
                    ResponseMessages.WORKFLOW_NOT_FOUND);
        }
        this.edgeService.setWorkflow(workflow);

        return this.edgeService;
    }

    @Path("{id}/executions")
    @AllowedRoles(roles = {AllowedRoles.DATA_SCIENTIST, AllowedRoles.DATA_OWNER})
    public WorkflowExecutionService executions(
            @PathParam("id") Integer id) throws AppException {
        Workflow workflow = workflowFacade.findById(id);
        if (workflow == null) {
            throw new AppException(Response.Status.BAD_REQUEST.getStatusCode(),
                    ResponseMessages.WORKFLOW_NOT_FOUND);
        }
        this.workflowExecutionService.setWorkflow(workflow);

        return this.workflowExecutionService;
    }
}