package software.wings.search.entities.related.audit;

import static io.harness.annotations.dev.HarnessTeam.PL;

import io.harness.annotations.dev.OwnedBy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldNameConstants;
import software.wings.search.framework.EntityBaseView;

@OwnedBy(PL)
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@FieldNameConstants(innerTypeName = "RelatedAuditViewKeys")
public class RelatedAuditView extends EntityBaseView {
  private String id;
  private String auditCreatedBy;
  private long auditCreatedAt;
  private String appId;
  private String affectedResourceId;
  private String affectedResourceName;
  private String affectedResourceType;
}
