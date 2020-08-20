package software.wings.service.impl;

import static io.harness.data.structure.EmptyPredicate.isEmpty;
import static io.harness.data.structure.EmptyPredicate.isNotEmpty;
import static io.harness.eraro.ErrorCode.ACCESS_DENIED;
import static io.harness.eraro.ErrorCode.DEFAULT_ERROR_CODE;
import static io.harness.eraro.ErrorCode.EXPIRED_TOKEN;
import static io.harness.eraro.ErrorCode.GENERAL_ERROR;
import static io.harness.eraro.ErrorCode.INVALID_CREDENTIAL;
import static io.harness.eraro.ErrorCode.INVALID_TOKEN;
import static io.harness.eraro.ErrorCode.TOKEN_ALREADY_REFRESHED_ONCE;
import static io.harness.eraro.ErrorCode.USER_DOES_NOT_EXIST;
import static io.harness.exception.WingsException.USER;
import static io.harness.exception.WingsException.USER_ADMIN;
import static io.harness.logging.AutoLogContext.OverrideBehavior.OVERRIDE_ERROR;
import static io.harness.persistence.HQuery.excludeAuthority;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static software.wings.app.ManagerCacheRegistrar.AUTH_TOKEN_CACHE;
import static software.wings.app.ManagerCacheRegistrar.USER_CACHE;
import static software.wings.beans.Account.GLOBAL_ACCOUNT_ID;
import static software.wings.beans.Application.GLOBAL_APP_ID;
import static software.wings.beans.Environment.GLOBAL_ENV_ID;
import static software.wings.security.PermissionAttribute.Action.CREATE;
import static software.wings.security.PermissionAttribute.Action.DELETE;
import static software.wings.security.PermissionAttribute.Action.EXECUTE_PIPELINE;
import static software.wings.security.PermissionAttribute.Action.EXECUTE_WORKFLOW;
import static software.wings.security.PermissionAttribute.Action.READ;
import static software.wings.security.PermissionAttribute.Action.UPDATE;
import static software.wings.security.PermissionAttribute.PermissionType.APPLICATION_CREATE_DELETE;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.InvalidClaimException;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEDecrypter;
import com.nimbusds.jose.KeyLengthException;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jwt.EncryptedJWT;
import io.harness.cache.HarnessCacheManager;
import io.harness.cvng.core.services.api.VerificationServiceSecretManager;
import io.harness.entity.ServiceSecretKey.ServiceType;
import io.harness.eraro.ErrorCode;
import io.harness.event.handler.impl.segment.SegmentHandler;
import io.harness.exception.InvalidRequestException;
import io.harness.exception.InvalidTokenException;
import io.harness.exception.WingsException;
import io.harness.logging.AutoLogContext;
import io.harness.persistence.HPersistence;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.mongodb.morphia.Key;
import software.wings.app.MainConfiguration;
import software.wings.beans.Account;
import software.wings.beans.Application;
import software.wings.beans.AuthToken;
import software.wings.beans.AuthToken.AuthTokenKeys;
import software.wings.beans.Environment;
import software.wings.beans.Environment.EnvironmentType;
import software.wings.beans.Event;
import software.wings.beans.Permission;
import software.wings.beans.Pipeline;
import software.wings.beans.Role;
import software.wings.beans.User;
import software.wings.beans.Workflow;
import software.wings.beans.security.AccountPermissions;
import software.wings.beans.security.AppPermission;
import software.wings.beans.security.UserGroup;
import software.wings.dl.GenericDbCache;
import software.wings.logcontext.UserLogContext;
import software.wings.security.AccountPermissionSummary;
import software.wings.security.AppPermissionSummary;
import software.wings.security.AppPermissionSummary.EnvInfo;
import software.wings.security.AppPermissionSummaryForUI;
import software.wings.security.GenericEntityFilter;
import software.wings.security.GenericEntityFilter.FilterType;
import software.wings.security.JWT_CATEGORY;
import software.wings.security.PermissionAttribute;
import software.wings.security.PermissionAttribute.Action;
import software.wings.security.PermissionAttribute.PermissionType;
import software.wings.security.SecretManager;
import software.wings.security.UserPermissionInfo;
import software.wings.security.UserRequestContext;
import software.wings.security.UserRequestInfo;
import software.wings.security.UserRestrictionInfo;
import software.wings.security.UserRestrictionInfo.UserRestrictionInfoBuilder;
import software.wings.security.UserThreadLocal;
import software.wings.service.impl.security.auth.AuthHandler;
import software.wings.service.intfc.ApiKeyService;
import software.wings.service.intfc.AuthService;
import software.wings.service.intfc.HarnessUserGroupService;
import software.wings.service.intfc.UsageRestrictionsService;
import software.wings.service.intfc.UserGroupService;
import software.wings.service.intfc.UserService;

import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.cache.Cache;
import javax.cache.expiry.AccessedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.crypto.spec.SecretKeySpec;

@Singleton
@Slf4j
public class AuthServiceImpl implements AuthService {
  private GenericDbCache dbCache;
  private HPersistence persistence;
  private UserService userService;
  private UserGroupService userGroupService;
  private HarnessCacheManager harnessCacheManager;
  private UsageRestrictionsService usageRestrictionsService;
  private Cache<String, AuthToken> authTokenCache;
  private Cache<String, User> userCache;
  private MainConfiguration configuration;
  private VerificationServiceSecretManager verificationServiceSecretManager;
  private AuthHandler authHandler;
  private HarnessUserGroupService harnessUserGroupService;
  private SecretManager secretManager;
  private static final String USER_PERMISSION_CACHE_NAME = "userPermissionCache".concat(":%s");
  private static final String USER_RESTRICTION_CACHE_NAME = "userRestrictionCache".concat(":%s");
  @Inject private ExecutorService executorService;
  @Inject private ApiKeyService apiKeyService;
  @Inject @Nullable private SegmentHandler segmentHandler;
  @Inject private AuditServiceHelper auditServiceHelper;

  @Inject
  public AuthServiceImpl(GenericDbCache dbCache, HPersistence persistence, UserService userService,
      UserGroupService userGroupService, UsageRestrictionsService usageRestrictionsService,
      HarnessCacheManager harnessCacheManager, @Named(AUTH_TOKEN_CACHE) Cache<String, AuthToken> authTokenCache,
      @Named(USER_CACHE) Cache<String, User> userCache, MainConfiguration configuration,
      VerificationServiceSecretManager verificationServiceSecretManager, AuthHandler authHandler,
      HarnessUserGroupService harnessUserGroupService, SecretManager secretManager) {
    this.dbCache = dbCache;
    this.persistence = persistence;
    this.userService = userService;
    this.userGroupService = userGroupService;
    this.usageRestrictionsService = usageRestrictionsService;
    this.harnessCacheManager = harnessCacheManager;
    this.authTokenCache = authTokenCache;
    this.userCache = userCache;
    this.configuration = configuration;
    this.verificationServiceSecretManager = verificationServiceSecretManager;
    this.authHandler = authHandler;
    this.harnessUserGroupService = harnessUserGroupService;
    this.secretManager = secretManager;
  }

  @UtilityClass
  public static final class Keys {
    public static final String HARNESS_EMAIL = "@harness.io";
    public static final String LOGIN_EVENT = "User Authenticated";
  }

  @Override
  public AuthToken validateToken(String tokenString) {
    if (tokenString.length() <= 32) {
      AuthToken authToken = getAuthToken(tokenString);
      if (authToken == null) {
        throw new WingsException(INVALID_TOKEN, USER);
      } else if (authToken.getExpireAt() <= System.currentTimeMillis()) {
        throw new WingsException(EXPIRED_TOKEN, USER);
      }
      return getAuthTokenWithUser(authToken);
    } else {
      return getAuthTokenWithUser(verifyToken(tokenString));
    }
  }

  private AuthToken getAuthToken(String authTokenId) {
    AuthToken authToken = null;
    if (authTokenCache != null) {
      authToken = authTokenCache.get(authTokenId);
    }

    if (authToken == null) {
      logger.info("Token with prefix {} not found in cache hence fetching it from db", authTokenId.substring(0, 5));
      authToken = getAuthTokenFromDB(authTokenId);
      addAuthTokenToCache(authToken);
    }
    return authToken;
  }

  private void addAuthTokenToCache(AuthToken authToken) {
    if (authToken != null && authTokenCache != null) {
      authTokenCache.put(authToken.getUuid(), authToken);
    }
  }

  private AuthToken getAuthTokenFromDB(String tokenString) {
    return persistence.getDatastore(AuthToken.class).get(AuthToken.class, tokenString);
  }

  private AuthToken verifyToken(String tokenString) {
    AuthToken authToken = verifyJWTToken(tokenString);
    if (authToken == null) {
      throw new WingsException(INVALID_TOKEN, USER);
    }
    return authToken;
  }

  private AuthToken getAuthTokenWithUser(AuthToken authToken) {
    User user = getUserFromCacheOrDB(authToken.getUserId());
    if (user == null) {
      throw new WingsException(USER_DOES_NOT_EXIST);
    }
    authToken.setUser(user);

    return authToken;
  }

  private User getUserFromCacheOrDB(String userId) {
    if (userCache == null) {
      logger.warn("userCache is null. Fetch from DB");
      return userService.get(userId);
    } else {
      User user;
      try {
        user = userCache.get(userId);
        if (user == null) {
          user = userService.get(userId);
          userCache.put(user.getUuid(), user);
        }
      } catch (Exception ex) {
        // If there was any exception, remove that entry from cache
        userCache.remove(userId);
        user = userService.get(userId);
        userCache.put(user.getUuid(), user);
      }
      return user;
    }
  }

  private void authorize(String accountId, String appId, String envId, User user,
      List<PermissionAttribute> permissionAttributes, UserRequestInfo userRequestInfo, boolean accountNullCheck) {
    if (!accountNullCheck) {
      if (accountId == null || dbCache.get(Account.class, accountId) == null) {
        logger.error("Auth Failure: non-existing accountId: {}", accountId);
        throw new WingsException(ACCESS_DENIED);
      }
    }

    if (appId != null && dbCache.get(Application.class, appId) == null) {
      logger.error("Auth Failure: non-existing appId: {}", appId);
      throw new WingsException(ACCESS_DENIED);
    }

    if (user.isAccountAdmin(accountId)) {
      return;
    }

    EnvironmentType envType = null;
    if (envId != null) {
      Environment env = dbCache.get(Environment.class, envId);
      envType = env.getEnvironmentType();
    }

    for (PermissionAttribute permissionAttribute : permissionAttributes) {
      if (!authorizeAccessType(accountId, appId, envId, envType, permissionAttribute,
              user.getRolesByAccountId(accountId), userRequestInfo)) {
        throw new WingsException(ACCESS_DENIED);
      }
    }
  }

  @Override
  public void authorize(String accountId, String appId, String envId, User user,
      List<PermissionAttribute> permissionAttributes, UserRequestInfo userRequestInfo) {
    authorize(accountId, appId, envId, user, permissionAttributes, userRequestInfo, true);
  }

  @Override
  public void authorize(String accountId, List<String> appIds, String envId, User user,
      List<PermissionAttribute> permissionAttributes, UserRequestInfo userRequestInfo) {
    if (accountId == null || dbCache.get(Account.class, accountId) == null) {
      logger.error("Auth Failure: non-existing accountId: {}", accountId);
      throw new WingsException(ACCESS_DENIED);
    }

    if (appIds != null) {
      for (String appId : appIds) {
        authorize(accountId, appId, envId, user, permissionAttributes, userRequestInfo, false);
      }
    }
  }

  private void authorize(String accountId, String appId, String entityId, User user,
      List<PermissionAttribute> permissionAttributes, boolean accountNullCheck) {
    UserPermissionInfo userPermissionInfo = authorizeAndGetUserPermissionInfo(accountId, appId, user, accountNullCheck);

    for (PermissionAttribute permissionAttribute : permissionAttributes) {
      if (!authorizeAccessType(appId, entityId, permissionAttribute, userPermissionInfo)) {
        logger.warn("User {} not authorized to access requested resource: {}", user.getName(), entityId);
        throw new WingsException(ACCESS_DENIED, USER);
      }
    }
  }

  @NotNull
  private UserPermissionInfo authorizeAndGetUserPermissionInfo(
      String accountId, String appId, User user, boolean accountNullCheck) {
    if (!accountNullCheck) {
      if (accountId == null || dbCache.get(Account.class, accountId) == null) {
        logger.error("Auth Failure: non-existing accountId: {}", accountId);
        throw new WingsException(ACCESS_DENIED, USER);
      }
    }

    if (appId != null && dbCache.get(Application.class, appId) == null) {
      logger.error("Auth Failure: non-existing appId: {}", appId);
      throw new WingsException(ACCESS_DENIED, USER);
    }

    if (user == null) {
      logger.error("No user context for authorization request for app: {}", appId);
      throw new WingsException(ACCESS_DENIED, USER);
    }

    UserRequestContext userRequestContext = user.getUserRequestContext();
    if (userRequestContext == null) {
      logger.error("User Request Context null for User {}", user.getName());
      throw new WingsException(ACCESS_DENIED, USER);
    }

    UserPermissionInfo userPermissionInfo = userRequestContext.getUserPermissionInfo();
    if (userPermissionInfo == null) {
      logger.error("User permission info null for User {}", user.getName());
      throw new WingsException(ACCESS_DENIED, USER);
    }
    return userPermissionInfo;
  }

  @Override
  public void authorize(
      String accountId, String appId, String entityId, User user, List<PermissionAttribute> permissionAttributes) {
    authorize(accountId, appId, entityId, user, permissionAttributes, true);
  }

  @Override
  public void authorize(String accountId, List<String> appIds, String entityId, User user,
      List<PermissionAttribute> permissionAttributes) {
    if (accountId == null || dbCache.get(Account.class, accountId) == null) {
      logger.error("Auth Failure: non-existing accountId: {}", accountId);
      throw new WingsException(ACCESS_DENIED, USER);
    }

    if (appIds != null) {
      for (String appId : appIds) {
        authorize(accountId, appId, entityId, user, permissionAttributes, false);
      }
    }
  }

  @Override
  public void validateDelegateToken(String accountId, String tokenString) {
    Account account = dbCache.get(Account.class, accountId);

    if (account == null || GLOBAL_ACCOUNT_ID.equals(accountId)) {
      throw new InvalidRequestException("Access denied", USER_ADMIN);
    }

    EncryptedJWT encryptedJWT;
    try {
      encryptedJWT = EncryptedJWT.parse(tokenString);
    } catch (ParseException e) {
      throw new InvalidTokenException("Invalid delegate token format", USER_ADMIN);
    }

    byte[] encodedKey;
    try {
      encodedKey = Hex.decodeHex(account.getAccountKey().toCharArray());
    } catch (DecoderException e) {
      throw new WingsException(DEFAULT_ERROR_CODE, USER_ADMIN, e);
    }

    JWEDecrypter decrypter;
    try {
      decrypter = new DirectDecrypter(new SecretKeySpec(encodedKey, 0, encodedKey.length, "AES"));
    } catch (KeyLengthException e) {
      throw new WingsException(DEFAULT_ERROR_CODE, USER_ADMIN, e);
    }

    try {
      encryptedJWT.decrypt(decrypter);
    } catch (JOSEException e) {
      throw new InvalidTokenException("Invalid delegate token", USER_ADMIN);
    }

    try {
      Date expirationDate = encryptedJWT.getJWTClaimsSet().getExpirationTime();
      if (System.currentTimeMillis() > expirationDate.getTime()) {
        throw new InvalidRequestException("Unauthorized", EXPIRED_TOKEN, null);
      }
    } catch (ParseException ex) {
      throw new InvalidRequestException("Unauthorized", ex, EXPIRED_TOKEN, null);
    }
  }

  @Override
  public void validateExternalServiceToken(String accountId, String externalServiceToken) {
    String jwtExternalServiceSecret = configuration.getPortal().getJwtExternalServiceSecret();
    if (isBlank(jwtExternalServiceSecret)) {
      throw new InvalidRequestException("incorrect portal setup");
    }
    try {
      Algorithm algorithm = Algorithm.HMAC256(jwtExternalServiceSecret);
      JWTVerifier verifier = JWT.require(algorithm).withIssuer("Harness Inc").build();
      verifier.verify(externalServiceToken);
      JWT decode = JWT.decode(externalServiceToken);
      if (decode.getExpiresAt().getTime() < System.currentTimeMillis()) {
        throw new WingsException(EXPIRED_TOKEN, USER_ADMIN);
      }
    } catch (Exception ex) {
      logger.warn("Error in verifying JWT token ", ex);
      throw ex instanceof JWTVerificationException ? new WingsException(INVALID_TOKEN) : new WingsException(ex);
    }
  }

  @Override
  public void validateLearningEngineServiceToken(String learningEngineServiceToken) {
    String jwtLearningEngineServiceSecret = verificationServiceSecretManager.getVerificationServiceSecretKey();
    if (StringUtils.isBlank(jwtLearningEngineServiceSecret)) {
      throw new InvalidRequestException("no secret key for service found for " + ServiceType.LEARNING_ENGINE);
    }
    try {
      Algorithm algorithm = Algorithm.HMAC256(jwtLearningEngineServiceSecret);
      JWTVerifier verifier =
          JWT.require(algorithm).withIssuer("Harness Inc").acceptIssuedAt(TimeUnit.MINUTES.toSeconds(10)).build();
      verifier.verify(learningEngineServiceToken);
      JWT decode = JWT.decode(learningEngineServiceToken);
      if (decode.getExpiresAt().getTime() < System.currentTimeMillis()) {
        throw new WingsException(EXPIRED_TOKEN, USER_ADMIN);
      }
    } catch (Exception ex) {
      logger.warn("Error in verifying JWT token ", ex);
      throw ex instanceof JWTVerificationException ? new WingsException(INVALID_TOKEN) : new WingsException(ex);
    }
  }

  @Override
  public void invalidateAllTokensForUser(String userId) {
    List<Key<AuthToken>> keyList =
        persistence.createQuery(AuthToken.class, excludeAuthority).filter(AuthTokenKeys.userId, userId).asKeyList();
    keyList.forEach(authToken -> invalidateToken(authToken.getId().toString()));
  }

  @Override
  public void invalidateToken(String utoken) {
    AuthToken authToken = validateToken(utoken);
    if (authToken != null) {
      persistence.delete(AuthToken.class, authToken.getUuid());
      if (authTokenCache != null) {
        authTokenCache.remove(authToken.getUuid());
      }
    }
  }

  private boolean authorizeAccessType(String accountId, String appId, String envId, EnvironmentType envType,
      PermissionAttribute permissionAttribute, List<Role> roles, UserRequestInfo userRequestInfo) {
    if (isEmpty(roles)) {
      return false;
    }
    return roles.stream()
        .filter(role
            -> roleAuthorizedWithAccessType(
                role, permissionAttribute, accountId, appId, envId, envType, userRequestInfo))
        .findFirst()
        .isPresent();
  }

  private boolean roleAuthorizedWithAccessType(Role role, PermissionAttribute permissionAttribute, String accountId,
      String appId, String envId, EnvironmentType envType, UserRequestInfo userRequestInfo) {
    if (role.getPermissions() == null) {
      return false;
    }

    Action reqAction = permissionAttribute.getAction();
    PermissionType permissionType = permissionAttribute.getPermissionType();

    for (Permission permission : role.getPermissions()) {
      if (permission.getPermissionScope() != permissionType
          || (permission.getAction() != Action.ALL && reqAction != permission.getAction())) {
        continue;
      }
      if (permissionType == PermissionType.APP) {
        if (userRequestInfo != null
            && (userRequestInfo.isAllAppsAllowed() || userRequestInfo.getAllowedAppIds().contains(appId))) {
          return true;
        }
        if (permission.getAppId() != null
            && (permission.getAppId().equals(GLOBAL_APP_ID) || permission.getAppId().equals(appId))) {
          return true;
        }
      } else if (permissionType == PermissionType.ENV) {
        if (userRequestInfo != null
            && (userRequestInfo.isAllEnvironmentsAllowed() || userRequestInfo.getAllowedEnvIds().contains(envId))) {
          return true;
        }

        if (permission.getEnvironmentType() != null && permission.getEnvironmentType() == envType) {
          return true;
        }

        if (permission.getEnvId() != null
            && (permission.getEnvId().equals(GLOBAL_ENV_ID) || permission.getEnvId().equals(envId))) {
          return true;
        }
      }
    }

    return false;
  }

  private Cache<String, UserPermissionInfo> getUserPermissionCache(String accountId) {
    return harnessCacheManager.getCache(String.format(USER_PERMISSION_CACHE_NAME, accountId), String.class,
        UserPermissionInfo.class, AccessedExpiryPolicy.factoryOf(Duration.ONE_HOUR));
  }

  private Cache<String, UserRestrictionInfo> getUserRestrictionCache(String accountId) {
    return harnessCacheManager.getCache(String.format(USER_RESTRICTION_CACHE_NAME, accountId), String.class,
        UserRestrictionInfo.class, AccessedExpiryPolicy.factoryOf(Duration.ONE_HOUR));
  }

  @Override
  public UserPermissionInfo getUserPermissionInfo(String accountId, User user, boolean cacheOnly) {
    Cache<String, UserPermissionInfo> userPermissionInfoCache = getUserPermissionCache(accountId);
    if (userPermissionInfoCache == null) {
      if (cacheOnly) {
        return null;
      }
      logger.error("UserInfoCache is null. This should not happen. Fall back to DB");
      return getUserPermissionInfoFromDB(accountId, user);
    }
    String key = user.getUuid();
    UserPermissionInfo value;
    try {
      value = userPermissionInfoCache.get(key);
      if (value == null) {
        if (cacheOnly) {
          return null;
        }

        value = getUserPermissionInfoFromDB(accountId, user);
        userPermissionInfoCache.put(key, value);
      }
      return value;
    } catch (Exception e) {
      logger.warn("Error in fetching user UserPermissionInfo from Cache for key:" + key, e);
    }

    // not found in cache. cache write through failed as well. rebuild anyway
    return getUserPermissionInfoFromDB(accountId, user);
  }

  @Override
  public UserRestrictionInfo getUserRestrictionInfo(
      String accountId, User user, UserPermissionInfo userPermissionInfo, boolean cacheOnly) {
    Cache<String, UserRestrictionInfo> userRestrictionInfoCache = getUserRestrictionCache(accountId);
    if (userRestrictionInfoCache == null) {
      if (cacheOnly) {
        return null;
      }
      logger.error("UserInfoCache is null. This should not happen. Fall back to DB");
      return getUserRestrictionInfoFromDB(accountId, user, userPermissionInfo);
    }

    String key = user.getUuid();
    UserRestrictionInfo value;
    try {
      value = userRestrictionInfoCache.get(key);
      if (value == null) {
        if (cacheOnly) {
          return null;
        }
        value = getUserRestrictionInfoFromDB(accountId, user, userPermissionInfo);
        userRestrictionInfoCache.put(key, value);
      }
      return value;
    } catch (Exception ignored) {
      logger.warn("Error in fetching user UserPermissionInfo from Cache for key:" + key, ignored);
    }

    // not found in cache. cache write through failed as well. rebuild anyway
    return getUserRestrictionInfoFromDB(accountId, user, userPermissionInfo);
  }

  @Override
  public void evictUserPermissionAndRestrictionCacheForAccount(
      String accountId, boolean rebuildUserPermissionInfo, boolean rebuildUserRestrictionInfo) {
    getUserPermissionCache(accountId).clear();
    getUserRestrictionCache(accountId).clear();
    apiKeyService.evictAndRebuildPermissionsAndRestrictions(
        accountId, rebuildUserPermissionInfo || rebuildUserRestrictionInfo);
  }

  @Override
  public void evictUserPermissionCacheForAccount(String accountId, boolean rebuildUserPermissionInfo) {
    getUserPermissionCache(accountId).clear();
    apiKeyService.evictAndRebuildPermissions(accountId, rebuildUserPermissionInfo);
  }

  private <T> void removeFromCache(Cache<String, T> cache, List<String> memberIds) {
    if (cache != null && isNotEmpty(memberIds)) {
      Set<String> keys = new HashSet<>(memberIds);
      cache.removeAll(keys);
    }
  }

  @Override
  public void evictUserPermissionAndRestrictionCacheForAccounts(Set<String> accountIds, List<String> memberIds) {
    if (isEmpty(accountIds)) {
      return;
    }
    accountIds.forEach(accountId -> evictUserPermissionAndRestrictionCacheForAccount(accountId, memberIds));
  }

  @Override
  public void evictPermissionAndRestrictionCacheForUserGroup(UserGroup userGroup) {
    evictUserPermissionAndRestrictionCacheForAccount(userGroup.getAccountId(), userGroup.getMemberIds());
    apiKeyService.evictPermissionsAndRestrictionsForUserGroup(userGroup);
  }

  @Override
  public void evictUserPermissionAndRestrictionCacheForAccount(String accountId, List<String> memberIds) {
    removeFromCache(getUserPermissionCache(accountId), memberIds);
    removeFromCache(getUserRestrictionCache(accountId), memberIds);
  }

  private UserPermissionInfo getUserPermissionInfoFromDB(String accountId, User user) {
    List<UserGroup> userGroups = getUserGroups(accountId, user);
    return authHandler.evaluateUserPermissionInfo(accountId, userGroups, user);
  }

  private List<UserGroup> getUserGroups(String accountId, User user) {
    List<UserGroup> userGroups = userGroupService.listByAccountId(accountId, user);

    if (isEmpty(userGroups) && !userService.isUserAssignedToAccount(user, accountId)) {
      // Check if its a harness user
      Optional<UserGroup> harnessUserGroup = getHarnessUserGroupsByAccountId(accountId, user);
      if (harnessUserGroup.isPresent()) {
        userGroups = Lists.newArrayList(harnessUserGroup.get());
      }
    }
    return userGroups;
  }

  private UserRestrictionInfo getUserRestrictionInfoFromDB(
      String accountId, User user, UserPermissionInfo userPermissionInfo) {
    List<UserGroup> userGroups = getUserGroups(accountId, user);
    return getUserRestrictionInfoFromDB(accountId, userPermissionInfo, userGroups);
  }

  @Override
  public UserRestrictionInfo getUserRestrictionInfoFromDB(
      String accountId, UserPermissionInfo userPermissionInfo, List<UserGroup> userGroupList) {
    UserRestrictionInfoBuilder userRestrictionInfoBuilder = UserRestrictionInfo.builder();

    // Restrictions for update permissions
    userRestrictionInfoBuilder.appEnvMapForUpdateAction(
        usageRestrictionsService.getAppEnvMapFromUserPermissions(accountId, userPermissionInfo, UPDATE));
    userRestrictionInfoBuilder.usageRestrictionsForUpdateAction(
        usageRestrictionsService.getUsageRestrictionsFromUserPermissions(UPDATE, userGroupList));

    // Restrictions for read permissions
    userRestrictionInfoBuilder.appEnvMapForReadAction(
        usageRestrictionsService.getAppEnvMapFromUserPermissions(accountId, userPermissionInfo, READ));
    userRestrictionInfoBuilder.usageRestrictionsForReadAction(
        usageRestrictionsService.getUsageRestrictionsFromUserPermissions(READ, userGroupList));

    return userRestrictionInfoBuilder.build();
  }

  private Optional<UserGroup> getHarnessUserGroupsByAccountId(String accountId, User user) {
    if (!harnessUserGroupService.isHarnessSupportUser(user.getUuid())
        || !harnessUserGroupService.isHarnessSupportEnabledForAccount(accountId)) {
      return Optional.empty();
    }
    AppPermission appPermission =
        AppPermission.builder()
            .appFilter(GenericEntityFilter.builder().filterType(FilterType.ALL).build())
            .permissionType(PermissionType.ALL_APP_ENTITIES)
            .actions(Sets.newHashSet(READ, UPDATE, DELETE, CREATE, EXECUTE_PIPELINE, EXECUTE_WORKFLOW))
            .build();

    AccountPermissions accountPermissions =
        AccountPermissions.builder().permissions(authHandler.getAllAccountPermissions()).build();
    UserGroup userGroup = UserGroup.builder()
                              .accountId(accountId)
                              .accountPermissions(accountPermissions)
                              .appPermissions(Sets.newHashSet(appPermission))
                              .build();
    return Optional.of(userGroup);
  }

  private boolean authorizeAccessType(String appId, String entityId, PermissionAttribute requiredPermissionAttribute,
      UserPermissionInfo userPermissionInfo) {
    if (requiredPermissionAttribute.isSkipAuth()) {
      return true;
    }

    Action requiredAction = requiredPermissionAttribute.getAction();
    PermissionType requiredPermissionType = requiredPermissionAttribute.getPermissionType();

    Map<String, AppPermissionSummary> appPermissionMap = userPermissionInfo.getAppPermissionMapInternal();
    AppPermissionSummary appPermissionSummary = appPermissionMap.get(appId);

    if (appPermissionSummary == null) {
      return false;
    }

    if (CREATE == requiredAction) {
      if (requiredPermissionType == PermissionType.SERVICE) {
        return appPermissionSummary.isCanCreateService();
      } else if (requiredPermissionType == PermissionType.PROVISIONER) {
        return appPermissionSummary.isCanCreateProvisioner();
      } else if (requiredPermissionType == PermissionType.ENV) {
        return appPermissionSummary.isCanCreateEnvironment();
      } else if (requiredPermissionType == PermissionType.WORKFLOW) {
        return appPermissionSummary.isCanCreateWorkflow();
      } else if (requiredPermissionType == PermissionType.PIPELINE) {
        return appPermissionSummary.isCanCreatePipeline();
      } else {
        String msg = "Unsupported app permission entity type: " + requiredPermissionType;
        logger.error(msg);
        throw new WingsException(msg);
      }
    }

    Map<Action, Set<String>> actionEntityIdMap;

    if (requiredPermissionType == PermissionType.SERVICE) {
      actionEntityIdMap = appPermissionSummary.getServicePermissions();
    } else if (requiredPermissionType == PermissionType.PROVISIONER) {
      actionEntityIdMap = appPermissionSummary.getProvisionerPermissions();
    } else if (requiredPermissionType == PermissionType.ENV) {
      Map<Action, Set<EnvInfo>> actionEnvPermissionsMap = appPermissionSummary.getEnvPermissions();
      if (isEmpty(actionEnvPermissionsMap)) {
        return false;
      }

      Set<EnvInfo> envInfoSet = actionEnvPermissionsMap.get(requiredAction);
      if (isEmpty(envInfoSet)) {
        return false;
      }

      Set<String> envIdSet = envInfoSet.stream().map(EnvInfo::getEnvId).collect(toSet());
      return envIdSet.contains(entityId);

    } else if (requiredPermissionType == PermissionType.WORKFLOW) {
      actionEntityIdMap = appPermissionSummary.getWorkflowPermissions();
    } else if (requiredPermissionType == PermissionType.PIPELINE) {
      actionEntityIdMap = appPermissionSummary.getPipelinePermissions();
    } else if (requiredPermissionType == PermissionType.DEPLOYMENT) {
      actionEntityIdMap = appPermissionSummary.getDeploymentPermissions();
    } else {
      String msg = "Unsupported app permission entity type: " + requiredPermissionType;
      logger.error(msg);
      throw new WingsException(msg);
    }

    if (isEmpty(actionEntityIdMap)) {
      return false;
    }

    Collection<String> entityIds = actionEntityIdMap.get(requiredAction);
    if (isEmpty(entityIds)) {
      return false;
    }

    return entityIds.contains(entityId);
  }

  private AuthToken verifyJWTToken(String token) {
    String jwtPasswordSecret = secretManager.getJWTSecret(JWT_CATEGORY.AUTH_SECRET);
    try {
      Algorithm algorithm = Algorithm.HMAC256(jwtPasswordSecret);
      JWTVerifier verifier = JWT.require(algorithm).withIssuer("Harness Inc").build();
      verifier.verify(token);
      String authToken = JWT.decode(token).getClaim("authToken").asString();
      return getAuthToken(authToken);
    } catch (UnsupportedEncodingException | JWTCreationException exception) {
      throw new WingsException(GENERAL_ERROR, exception).addParam("message", "JWTToken validation failed");
    } catch (JWTDecodeException | SignatureVerificationException | InvalidClaimException e) {
      throw new WingsException(INVALID_CREDENTIAL, USER, e)
          .addParam("message", "Invalid JWTToken received, failed to decode the token");
    }
  }

  @Override
  public User refreshToken(String oldToken) {
    if (oldToken.length() <= 32) {
      AuthToken authToken = getAuthToken(oldToken);
      if (authToken == null) {
        throw new WingsException(EXPIRED_TOKEN, USER);
      }

      User user = getUserFromAuthToken(authToken);
      user.setToken(authToken.getUuid());
      return user;
    }

    AuthToken authToken = verifyToken(oldToken);
    if (authToken.isRefreshed()) {
      throw new WingsException(TOKEN_ALREADY_REFRESHED_ONCE, USER);
    }
    User user = getUserFromAuthToken(authToken);
    authToken.setRefreshed(true);
    saveAuthToken(authToken);
    addAuthTokenToCache(authToken);
    return generateBearerTokenForUser(user);
  }

  private String saveAuthToken(AuthToken authToken) {
    return persistence.save(authToken);
  }

  private User getUserFromAuthToken(AuthToken authToken) {
    User user = getUserFromCacheOrDB(authToken.getUserId());
    if (user == null) {
      logger.warn("No user found for userId:" + authToken.getUserId());
      throw new WingsException(USER_DOES_NOT_EXIST, USER);
    }
    return user;
  }

  @Override
  public User generateBearerTokenForUser(User user) {
    String acctId = user == null ? null : user.getDefaultAccountId();
    String uuid = user == null ? null : user.getUuid();
    try (AutoLogContext ignore = new UserLogContext(acctId, uuid, OVERRIDE_ERROR)) {
      logger.info("Generating bearer token");
      AuthToken authToken = new AuthToken(
          user.getLastAccountId(), user.getUuid(), configuration.getPortal().getAuthTokenExpiryInMillis());
      authToken.setJwtToken(generateJWTSecret(authToken));
      saveAuthToken(authToken);
      boolean isFirstLogin = user.getLastLogin() == 0L;
      user.setLastLogin(System.currentTimeMillis());
      userService.update(user);

      userService.evictUserFromCache(user.getUuid());
      user.setToken(authToken.getJwtToken());

      user.setFirstLogin(isFirstLogin);
      if (!user.getEmail().endsWith(Keys.HARNESS_EMAIL)) {
        executorService.submit(() -> {
          String accountId = user.getLastAccountId();
          if (isEmpty(accountId)) {
            logger.warn("last accountId is null for User {}", user.getUuid());
            return;
          }

          Account account = dbCache.get(Account.class, accountId);
          if (account == null) {
            logger.warn("last account is null for User {}", user.getUuid());
            return;
          }
          try {
            if (segmentHandler != null) {
              Map<String, String> properties = new HashMap<>();
              properties.put(SegmentHandler.Keys.GROUP_ID, accountId);

              Map<String, Boolean> integrations = new HashMap<>();
              integrations.put(SegmentHandler.Keys.NATERO, true);
              integrations.put(SegmentHandler.Keys.SALESFORCE, false);

              segmentHandler.reportTrackEvent(account, Keys.LOGIN_EVENT, user, properties, integrations);
            }
          } catch (Exception e) {
            logger.error("Exception while reporting track event for User {} login", user.getUuid(), e);
          }
        });
      }

      return user;
    }
  }

  private String generateJWTSecret(AuthToken authToken) {
    String jwtAuthSecret = secretManager.getJWTSecret(JWT_CATEGORY.AUTH_SECRET);
    int duration = JWT_CATEGORY.AUTH_SECRET.getValidityDuration();
    try {
      Algorithm algorithm = Algorithm.HMAC256(jwtAuthSecret);
      return JWT.create()
          .withIssuer("Harness Inc")
          .withIssuedAt(new Date())
          .withExpiresAt(new Date(System.currentTimeMillis() + duration))
          .withClaim("authToken", authToken.getUuid())
          .withClaim("usrId", authToken.getUserId())
          .withClaim("env", configuration.getEnvPath())
          .sign(algorithm);
    } catch (UnsupportedEncodingException | JWTCreationException exception) {
      throw new WingsException(GENERAL_ERROR, exception).addParam("message", "JWTToken could not be generated");
    }
  }

  @Override
  public void checkIfUserAllowedToDeployToEnv(String appId, String envId) {
    if (isEmpty(envId)) {
      return;
    }

    User user = UserThreadLocal.get();
    if (user == null) {
      return;
    }

    Set<String> deploymentEnvExecutePermissions = user.getUserRequestContext()
                                                      .getUserPermissionInfo()
                                                      .getAppPermissionMapInternal()
                                                      .get(appId)
                                                      .getDeploymentExecutePermissionsForEnvs();

    if (isEmpty(deploymentEnvExecutePermissions)) {
      throw new WingsException(ErrorCode.ACCESS_DENIED, USER);
    }

    if (!deploymentEnvExecutePermissions.contains(envId)) {
      throw new WingsException(ErrorCode.ACCESS_DENIED, USER);
    }
  }

  @Override
  public void checkIfUserAllowedToDeployWorkflowToEnv(String appId, String envId) {
    if (isEmpty(envId)) {
      return;
    }

    User user = UserThreadLocal.get();
    if (user == null) {
      return;
    }

    Set<String> workflowExecutePermissionsForEnvs = user.getUserRequestContext()
                                                        .getUserPermissionInfo()
                                                        .getAppPermissionMapInternal()
                                                        .get(appId)
                                                        .getWorkflowExecutePermissionsForEnvs();

    if (isEmpty(workflowExecutePermissionsForEnvs) || !workflowExecutePermissionsForEnvs.contains(envId)) {
      throw new InvalidRequestException(
          "User doesn't have rights to execute Workflow in this Environment", ErrorCode.ACCESS_DENIED, USER);
    }
  }

  @Override
  public void checkIfUserAllowedToDeployPipelineToEnv(String appId, String envId) {
    if (isEmpty(envId)) {
      return;
    }

    User user = UserThreadLocal.get();
    if (user == null) {
      return;
    }

    Set<String> pipelineExecutePermissionsForEnvs = user.getUserRequestContext()
                                                        .getUserPermissionInfo()
                                                        .getAppPermissionMapInternal()
                                                        .get(appId)
                                                        .getPipelineExecutePermissionsForEnvs();

    if (isEmpty(pipelineExecutePermissionsForEnvs) || !pipelineExecutePermissionsForEnvs.contains(envId)) {
      throw new InvalidRequestException(
          "User doesn't have rights to execute Pipeline in this Environment", ErrorCode.ACCESS_DENIED, USER);
    }
  }

  @Override
  public void checkIfUserCanCreateEnv(String appId, EnvironmentType envType) {
    User user = UserThreadLocal.get();
    if (user == null) {
      return;
    }

    if (envType == null) {
      throw new WingsException("No environment type specified", USER);
    }

    Set<EnvironmentType> envCreatePermissions = user.getUserRequestContext()
                                                    .getUserPermissionInfo()
                                                    .getAppPermissionMapInternal()
                                                    .get(appId)
                                                    .getEnvCreatePermissionsForEnvTypes();

    if (isEmpty(envCreatePermissions)) {
      throw new WingsException(ErrorCode.ACCESS_DENIED, USER);
    }

    if (envCreatePermissions.contains(EnvironmentType.ALL)) {
      return;
    }

    if (!envCreatePermissions.contains(envType)) {
      throw new WingsException(ErrorCode.ACCESS_DENIED, USER);
    }
  }

  @Override
  public void checkWorkflowPermissionsForEnv(String appId, Workflow workflow, Action action) {
    if (workflow == null) {
      return;
    }
    boolean envTemplatized = authHandler.isEnvTemplatized(workflow);
    String envId = workflow.getEnvId();

    if (!envTemplatized && isEmpty(envId)) {
      return;
    }

    User user = UserThreadLocal.get();
    if (user == null) {
      return;
    }

    AppPermissionSummary appPermissionSummary =
        user.getUserRequestContext().getUserPermissionInfo().getAppPermissionMapInternal().get(appId);
    if (envTemplatized) {
      if (appPermissionSummary.isCanCreateTemplatizedWorkflow()) {
        return;
      } else {
        throw new WingsException(ErrorCode.ACCESS_DENIED, USER);
      }
    }

    Set<String> allowedEnvIds;
    switch (action) {
      case CREATE:
        allowedEnvIds = appPermissionSummary.getWorkflowCreatePermissionsForEnvs();
        break;
      case UPDATE:
        allowedEnvIds = appPermissionSummary.getWorkflowUpdatePermissionsForEnvs();
        break;
      default:
        return;
    }

    if (isEmpty(allowedEnvIds) || !allowedEnvIds.contains(envId)) {
      throw new WingsException(ErrorCode.ACCESS_DENIED, USER);
    }
  }

  @Override
  public void checkIfUserCanCloneWorkflowToOtherApp(String targetAppId, Workflow workflow) {
    if (workflow == null) {
      return;
    }
    boolean envTemplatized = authHandler.isEnvTemplatized(workflow);

    if (!envTemplatized) {
      return;
    }

    User user = UserThreadLocal.get();
    if (user == null) {
      return;
    }

    AppPermissionSummary appPermissionSummary =
        user.getUserRequestContext().getUserPermissionInfo().getAppPermissionMapInternal().get(targetAppId);

    if (!appPermissionSummary.isCanCreateTemplatizedWorkflow()) {
      throw new WingsException(ErrorCode.ACCESS_DENIED, USER);
    }
  }

  @Override
  public void checkPipelinePermissionsForEnv(String appId, Pipeline pipeline, Action action) {
    User user = UserThreadLocal.get();

    if (user == null) {
      return;
    }

    AppPermissionSummary appPermissionSummary =
        user.getUserRequestContext().getUserPermissionInfo().getAppPermissionMapInternal().get(appId);
    Set<String> allowedEnvIds;

    switch (action) {
      case CREATE:
        allowedEnvIds = appPermissionSummary.getPipelineCreatePermissionsForEnvs();
        break;
      case UPDATE:
        allowedEnvIds = appPermissionSummary.getPipelineUpdatePermissionsForEnvs();
        break;
      default:
        return;
    }

    if (!authHandler.checkIfPipelineHasOnlyGivenEnvs(pipeline, allowedEnvIds)) {
      throw new WingsException(ErrorCode.ACCESS_DENIED, USER);
    }
  }

  @Override
  public void auditLogin(List<String> accountIds, User loggedInUser) {
    if (Objects.nonNull(loggedInUser) && Objects.nonNull(accountIds)) {
      accountIds.forEach(accountId
          -> auditServiceHelper.reportForAuditingUsingAccountId(accountId, null, loggedInUser, Event.Type.LOGIN));
    }
  }

  @Override
  public void authorizeAppAccess(String accountId, String appId, User user, Action action) {
    UserPermissionInfo userPermissionInfo = authorizeAndGetUserPermissionInfo(accountId, appId, user, false);

    Map<String, AppPermissionSummaryForUI> appPermissionMap = userPermissionInfo.getAppPermissionMap();
    if (appPermissionMap == null || !appPermissionMap.containsKey(appId)) {
      logger.error("Auth Failure: User does not have access to app {}", appId);
      throw new WingsException(ACCESS_DENIED, USER);
    }

    if (Action.UPDATE == action) {
      AccountPermissionSummary accountPermissionSummary = userPermissionInfo.getAccountPermissionSummary();

      if (accountPermissionSummary == null || isEmpty(accountPermissionSummary.getPermissions())
          || !accountPermissionSummary.getPermissions().contains(APPLICATION_CREATE_DELETE)) {
        logger.error("Auth Failure: User does not have access to update {}", appId);
        throw new WingsException(ACCESS_DENIED, USER);
      }
    }
  }
}
