package com.lowtuna.jsonblob.resource;

import com.codahale.metrics.annotation.Timed;
import com.google.common.collect.Lists;
import com.lowtuna.dropwizard.extras.config.GoogleAnalyticsConfig;
import com.lowtuna.jsonblob.core.FileSystemJsonBlobManager;
import com.lowtuna.jsonblob.core.MongoDbJsonBlobManager;
import com.lowtuna.jsonblob.view.ApiView;
import com.sun.jersey.api.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.util.Arrays;
import java.util.LinkedList;

@Path("/api")
@Slf4j
public class ApiResource {
    private final MongoDbJsonBlobManager mongoDbBlobManager;
    private final FileSystemJsonBlobManager fileSystemBlobManager;
    private final GoogleAnalyticsConfig gaConfig;

    public ApiResource(MongoDbJsonBlobManager mongoDbBlobManager, FileSystemJsonBlobManager fileSystemBlobManager, GoogleAnalyticsConfig gaConfig) {
        this.gaConfig = gaConfig;
        this.mongoDbBlobManager = mongoDbBlobManager;
        this.fileSystemBlobManager = fileSystemBlobManager;
    }

    @GET
    @Timed
    public ApiView getApiView() {
        return new ApiView(gaConfig.getWebPropertyID(), "api", gaConfig.getCustomTrackingCodes());
    }

    @POST
    @Path("jsonBlob")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Timed
    public Response createJsonBlob(String json) {
        try {
            String blobId = fileSystemBlobManager.createBlob(json);
            return Response.created(UriBuilder.fromResource(JsonBlobResource.class).build(blobId)).entity(json).header("X-jsonblob", blobId).build();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        } catch (IllegalStateException e) {
            return Response.serverError().build();
        }
    }

    @Path("{path: .*}")
    @Timed
    public JsonBlobResource getJsonBlobResource(@PathParam("path") String path, @HeaderParam("X-jsonblob") String jsonBlobId) {
        LinkedList<String> potentialIds = Lists.newLinkedList(Arrays.asList(path.split("/")));
        if (StringUtils.isNotEmpty(jsonBlobId)) {
            potentialIds.addFirst(jsonBlobId);
        }

        for (String candidate: potentialIds) {
            if (fileSystemBlobManager.blobExists(candidate)) {
                log.debug("Using FileSystemJsonBlobManager for loading blob");
                return new JsonBlobResource(candidate, fileSystemBlobManager);
            }
        }

        for (String candidate: potentialIds) {
            try {
                new ObjectId(candidate);
                log.debug("Using MongoDbJsonBlobManager for loading blob");
                return new JsonBlobResource(candidate, mongoDbBlobManager);
            } catch (IllegalArgumentException iae) {
                //try the next part or fall out of the loop
            }
        }

        throw new NotFoundException();
    }

}
