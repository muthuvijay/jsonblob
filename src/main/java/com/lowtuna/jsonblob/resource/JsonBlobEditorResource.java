package com.lowtuna.jsonblob.resource;

import com.codahale.metrics.annotation.Timed;
import com.lowtuna.dropwizard.extras.config.GoogleAnalyticsConfig;
import com.lowtuna.jsonblob.core.BlobNotFoundException;
import com.lowtuna.jsonblob.core.FileSystemJsonBlobManager;
import com.lowtuna.jsonblob.core.MongoDbJsonBlobManager;
import com.lowtuna.jsonblob.view.AboutView;
import com.lowtuna.jsonblob.view.EditorView;
import com.sun.jersey.api.NotFoundException;
import lombok.RequiredArgsConstructor;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

@Path("/")
@RequiredArgsConstructor
public class JsonBlobEditorResource {
    private final FileSystemJsonBlobManager fileSystemBlobManager;
    private final MongoDbJsonBlobManager mongoDbBlobManager;
    private final GoogleAnalyticsConfig gaConfig;

    @GET
    @Timed
    public EditorView defaultEditor() {
        return new EditorView(gaConfig.getWebPropertyID(), "editor", gaConfig.getCustomTrackingCodes());
    }

    @GET
    @Timed
    @Path("new")
    public EditorView emptyEditor() {
        EditorView view = new EditorView(gaConfig.getWebPropertyID(), "editor", gaConfig.getCustomTrackingCodes());
        view.setJsonBlob("{}");
        return view;
    }

    @GET
    @Timed
    @Path("about")
    public AboutView about() {
        AboutView view = new AboutView(gaConfig.getWebPropertyID(), "editor", gaConfig.getCustomTrackingCodes(), mongoDbBlobManager.getBlobAccessTtl(), false);
        return view;
    }

    @GET
    @Timed
    @Path("{blobId}")
    public EditorView blobEditor(@PathParam("blobId") String blobId) {
        try {
            String json = fileSystemBlobManager.blobExists(blobId) ? fileSystemBlobManager.getBlob(blobId) : mongoDbBlobManager.getBlob(blobId);

            EditorView view = new EditorView(gaConfig.getWebPropertyID(), "editor", gaConfig.getCustomTrackingCodes());
            view.setBlobId(blobId);
            view.setJsonBlob(json);
            return view;
        } catch (BlobNotFoundException e) {
            throw new NotFoundException();
        }
    }
}
