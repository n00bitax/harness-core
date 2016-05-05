package software.wings.resources;

import static javax.ws.rs.core.MediaType.MULTIPART_FORM_DATA;
import static software.wings.beans.ConfigFile.DEFAULT_TEMPLATE_ID;
import static software.wings.beans.SearchFilter.Operator.EQ;

import com.google.inject.Inject;

import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.wings.beans.ConfigFile;
import software.wings.beans.PageRequest;
import software.wings.beans.PageResponse;
import software.wings.beans.RestResponse;
import software.wings.service.intfc.ConfigService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.GeneralSecurityException;
import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.Encoded;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;

/**
 * Application Resource class.
 *
 * @author Rishi
 */

@Path("/configs")
@Produces("application/json")
public class ConfigResource {
  private final Logger logger = LoggerFactory.getLogger(getClass());
  @Inject private ConfigService configService;

  @GET
  public RestResponse<PageResponse<ConfigFile>> fetchConfigs(
      @QueryParam("entity_id") String entityId, @BeanParam PageRequest<ConfigFile> pageRequest) {
    pageRequest.addFilter("entityId", entityId, EQ);
    return new RestResponse<>(configService.list(pageRequest));
  }

  @POST
  @Consumes(MULTIPART_FORM_DATA)
  public RestResponse<String> uploadConfig(@QueryParam("entity_id") String entityId,
      @DefaultValue(DEFAULT_TEMPLATE_ID) @QueryParam("template_id") String templateId,
      @FormDataParam("fileName") String fileName, @FormDataParam("relativePath") String relativePath,
      @FormDataParam("md5") String md5, @FormDataParam("file") InputStream uploadedInputStream,
      @FormDataParam("file") FormDataContentDisposition fileDetail) {
    ConfigFile configFile = ConfigFile.ConfigFileBuilder.aConfigFile()
                                .withEntityId(entityId)
                                .withTemplateId(templateId)
                                .withName(fileName)
                                .withRelativePath(relativePath)
                                .withChecksum(md5)
                                .build();
    String fileId = configService.save(configFile, uploadedInputStream);
    return new RestResponse<>(fileId);
  }

  @GET
  @Path("{config_id}")
  public RestResponse<ConfigFile> fetchConfig(@PathParam("config_id") String configId) {
    return new RestResponse<>(configService.get(configId));
  }

  @PUT
  @Path("{config_id}")
  @Consumes(MULTIPART_FORM_DATA)
  public void updateConfig(@PathParam("config_id") String configId, @FormDataParam("fileName") String fileName,
      @FormDataParam("relativePath") String relativePath, @FormDataParam("md5") String md5,
      @FormDataParam("file") InputStream uploadedInputStream,
      @FormDataParam("file") FormDataContentDisposition fileDetail) {
    ConfigFile configFile = ConfigFile.ConfigFileBuilder.aConfigFile()
                                .withUuid(configId)
                                .withName(fileName)
                                .withRelativePath(relativePath)
                                .withChecksum(md5)
                                .build();
    configService.update(configFile, uploadedInputStream);
  }

  @DELETE
  @Path("{config_id}")
  public void delete(@PathParam("config_id") String configId) {
    configService.delete(configId);
  }

  @GET
  @Path("download/{applicationId}")
  @Encoded
  public Response download(@PathParam("applicationId") String applicationId)
      throws IOException, GeneralSecurityException {
    try {
      URL url = this.getClass().getResource("/temp-config.txt");
      ResponseBuilder response = Response.ok(new File(url.toURI()), MediaType.APPLICATION_OCTET_STREAM);
      response.header("Content-Disposition", "attachment; filename=app.config");
      return response.build();
    } catch (URISyntaxException ex) {
      return Response.noContent().build();
    }
  }
}
