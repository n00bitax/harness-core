package software.wings.yaml.handler.defaults;

import static io.harness.rule.OwnerRule.RAMA;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static software.wings.beans.Application.GLOBAL_APP_ID;
import static software.wings.beans.Environment.GLOBAL_ENV_ID;
import static software.wings.utils.WingsTestConstants.ACCOUNT_ID;

import com.google.inject.Inject;

import io.harness.category.element.UnitTests;
import io.harness.exception.HarnessException;
import io.harness.rule.Owner;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import software.wings.beans.SettingAttribute;
import software.wings.beans.StringValue;
import software.wings.beans.defaults.Defaults.Yaml;
import software.wings.beans.yaml.ChangeContext;
import software.wings.beans.yaml.GitFileChange;
import software.wings.beans.yaml.YamlType;
import software.wings.rules.SetupScheduler;
import software.wings.service.impl.yaml.handler.defaults.DefaultVariablesYamlHandler;
import software.wings.service.impl.yaml.service.YamlHelper;
import software.wings.service.intfc.SettingsService;
import software.wings.settings.SettingValue;
import software.wings.yaml.handler.BaseYamlHandlerTest;

import java.io.IOException;
import java.util.List;
import javax.validation.ConstraintViolationException;

/**
 * @author rktummala on 1/19/18
 */
@SetupScheduler
public class AccountDefaultVarYamlHandlerTest extends BaseYamlHandlerTest {
  @Mock YamlHelper yamlHelper;
  @InjectMocks @Inject private SettingsService settingsService;
  @InjectMocks @Inject private DefaultVariablesYamlHandler yamlHandler;

  private SettingAttribute v1_settingAttribute1;
  private SettingAttribute v1_settingAttribute2;
  private SettingAttribute v1_settingAttribute3;
  private List<SettingAttribute> v1_settingAttributeList;

  private SettingAttribute v2_settingAttribute1;
  private SettingAttribute v2_settingAttribute2;
  private SettingAttribute v2_settingAttribute3;
  private List<SettingAttribute> v2_settingAttributeList;

  private String v1_validYamlContent = "harnessApiVersion: '1.0'\n"
      + "type: ACCOUNT_DEFAULTS\n"
      + "defaults:\n"
      + "- name: var1\n"
      + "  value: value1\n"
      + "- name: var2\n"
      + "  value: value2\n"
      + "- name: var3\n"
      + "  value: value3";
  private String v2_validYamlContent = "harnessApiVersion: '1.0'\n"
      + "type: ACCOUNT_DEFAULTS\n"
      + "defaults:\n"
      + "- name: var1\n"
      + "  value: modified\n"
      + "- name: var4\n"
      + "  value: add\n"
      + "- name: var3\n"
      + "  value: value3";
  private String validYamlFilePath = "Setup/Defaults.yaml";
  private String invalidYamlContent = "defaults:\n"
      + "  - name1: var1\n"
      + "    value: value1\n"
      + "harnessApiVersion: '1.0'\n"
      + "type: ACCOUNT_DEFAULTS";

  @Before
  public void setUp() throws IOException {
    v1_settingAttribute1 = createNewSettingAttribute("var1", "value1");
    v1_settingAttribute2 = createNewSettingAttribute("var2", "value2");
    v1_settingAttribute3 = createNewSettingAttribute("var3", "value3");
    v1_settingAttributeList = asList(v1_settingAttribute1, v1_settingAttribute2, v1_settingAttribute3);

    v2_settingAttribute1 = createNewSettingAttribute("var1", "modified");
    v2_settingAttribute2 = createNewSettingAttribute("var4", "add");
    v2_settingAttribute3 = createNewSettingAttribute("var3", "value3");
    v2_settingAttributeList = asList(v2_settingAttribute1, v2_settingAttribute2, v2_settingAttribute3);
  }

  private SettingAttribute createNewSettingAttribute(String name, String value) {
    SettingValue settingValue = StringValue.Builder.aStringValue().withValue(value).build();
    return SettingAttribute.Builder.aSettingAttribute()
        .withAppId(GLOBAL_APP_ID)
        .withName(name)
        .withValue(settingValue)
        .withAccountId(ACCOUNT_ID)
        .withEnvId(GLOBAL_ENV_ID)
        .build();
  }

  @Test
  @Owner(developers = RAMA)
  @Category(UnitTests.class)
  public void testCRUDAndGet() throws HarnessException, IOException {
    GitFileChange gitFileChange = new GitFileChange();
    gitFileChange.setFileContent(v1_validYamlContent);
    gitFileChange.setFilePath(validYamlFilePath);
    gitFileChange.setAccountId(ACCOUNT_ID);

    ChangeContext<Yaml> changeContext = new ChangeContext<>();
    changeContext.setChange(gitFileChange);
    changeContext.setYamlType(YamlType.ACCOUNT_DEFAULTS);
    changeContext.setYamlSyncHandler(yamlHandler);

    Yaml yamlObject = (Yaml) getYaml(v1_validYamlContent, Yaml.class);
    changeContext.setYaml(yamlObject);

    List<SettingAttribute> createdSettingAttributes = yamlHandler.upsertFromYaml(changeContext, asList(changeContext));
    compareSettingAttributes(v1_settingAttributeList, createdSettingAttributes);

    Yaml yaml = yamlHandler.toYaml(this.v1_settingAttributeList, GLOBAL_APP_ID);
    assertThat(yaml).isNotNull();
    assertThat(yaml.getType()).isNotNull();

    String yamlContent = getYamlContent(yaml);
    assertThat(yamlContent).isNotNull();
    yamlContent = yamlContent.substring(0, yamlContent.length() - 1);
    assertThat(yamlContent).isEqualTo(v1_validYamlContent);

    List<SettingAttribute> settingAttributesFromGet = yamlHandler.get(ACCOUNT_ID, validYamlFilePath);
    compareSettingAttributes(v1_settingAttributeList, settingAttributesFromGet);

    gitFileChange.setFileContent(v2_validYamlContent);

    yamlObject = (Yaml) getYaml(v2_validYamlContent, Yaml.class);
    changeContext.setYaml(yamlObject);

    List<SettingAttribute> v2_createdSettingAttributes =
        yamlHandler.upsertFromYaml(changeContext, asList(changeContext));
    compareSettingAttributes(v2_settingAttributeList, v2_createdSettingAttributes);

    yamlHandler.delete(changeContext);
    List<SettingAttribute> settingAttributeList = yamlHandler.get(ACCOUNT_ID, validYamlFilePath);
    assertThat(settingAttributeList.isEmpty()).isTrue();
  }

  @Test
  @Owner(developers = RAMA)
  @Category(UnitTests.class)
  public void testFailures() throws HarnessException, IOException {
    // Invalid yaml path
    GitFileChange gitFileChange = new GitFileChange();
    gitFileChange.setFileContent(invalidYamlContent);
    gitFileChange.setFilePath(validYamlFilePath);
    gitFileChange.setAccountId(ACCOUNT_ID);

    ChangeContext<Yaml> changeContext = new ChangeContext();
    changeContext.setChange(gitFileChange);
    changeContext.setYamlType(YamlType.ACCOUNT_DEFAULTS);
    changeContext.setYamlSyncHandler(yamlHandler);

    Yaml yamlObject = (Yaml) getYaml(v1_validYamlContent, Yaml.class);
    changeContext.setYaml(yamlObject);

    // Invalid yaml content
    yamlObject = (Yaml) getYaml(invalidYamlContent, Yaml.class);
    changeContext.setYaml(yamlObject);
    thrown.expect(ConstraintViolationException.class);
    yamlHandler.upsertFromYaml(changeContext, asList(changeContext));
  }

  private void compareSettingAttributes(List<SettingAttribute> lhs, List<SettingAttribute> rhs) {
    assertThat(rhs).hasSize(lhs.size());
    assertThat(lhs).containsAll(rhs);
  }
}
