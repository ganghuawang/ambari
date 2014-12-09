/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.Singleton;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.api.services.AmbariMetaInfo;

import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.ConfigurationRequest;
import org.apache.ambari.server.orm.dao.ClusterDAO;
import org.apache.ambari.server.orm.entities.ClusterConfigEntity;
import org.apache.ambari.server.state.PropertyInfo.PropertyType;
import org.apache.ambari.server.state.configgroup.ConfigGroup;
import org.apache.ambari.server.upgrade.UpgradeCatalog170;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class that works with config traversals.
 */
@Singleton
public class ConfigHelper {

  private Clusters clusters = null;
  private AmbariMetaInfo ambariMetaInfo = null;
  private ClusterDAO clusterDAO = null;
  private static final String DELETED = "DELETED_";
  public static final String CLUSTER_DEFAULT_TAG = "tag";
  private final boolean STALE_CONFIGS_CACHE_ENABLED;
  private final int STALE_CONFIGS_CACHE_EXPIRATION_TIME = 300;
  private final Cache<ServiceComponentHost, Boolean> staleConfigsCache;

  private static final Logger LOG =
    LoggerFactory.getLogger(ConfigHelper.class);

  @Inject
  public ConfigHelper(Clusters c, AmbariMetaInfo metaInfo, Configuration configuration, ClusterDAO clusterDAO) {
    clusters = c;
    ambariMetaInfo = metaInfo;
    this.clusterDAO = clusterDAO;
    STALE_CONFIGS_CACHE_ENABLED = configuration.isStaleConfigCacheEnabled();
    staleConfigsCache = CacheBuilder.newBuilder().
      expireAfterWrite(STALE_CONFIGS_CACHE_EXPIRATION_TIME, TimeUnit.SECONDS).build();
  }

  /**
   * Gets the desired tags for a cluster and host
   * @param cluster the cluster
   * @param hostName the host name
   * @return a map of tag type to tag names with overrides
   * @throws AmbariException
   */
  public Map<String, Map<String, String>> getEffectiveDesiredTags(
      Cluster cluster, String hostName) throws AmbariException {
    
    Host host = clusters.getHost(hostName);
    
    return getEffectiveDesiredTags(cluster, host.getDesiredHostConfigs(cluster));
  }

  /**
   * Gets the desired tags for a cluster and overrides for a host
   * @param cluster the cluster
   * @param hostConfigOverrides the host overrides applied using config groups
   * @return a map of tag type to tag names with overrides
   */
  private Map<String, Map<String, String>> getEffectiveDesiredTags(
      Cluster cluster, Map<String, HostConfig> hostConfigOverrides) {
    
    Map<String, DesiredConfig> clusterDesired = cluster.getDesiredConfigs();
    
    Map<String, Map<String,String>> resolved = new TreeMap<String, Map<String, String>>();
    
    // Do not use host component config mappings.  Instead, the rules are:
    // 1) Use the cluster desired config
    // 2) override (1) with config-group overrides
    
    for (Entry<String, DesiredConfig> clusterEntry : clusterDesired.entrySet()) {
      String type = clusterEntry.getKey();
      String tag = clusterEntry.getValue().getTag();

      // 1) start with cluster config
      Config config = cluster.getConfig(type, tag);
      if (null == config) {
        continue;
      }

      Map<String, String> tags = new LinkedHashMap<String, String>();

      tags.put(CLUSTER_DEFAULT_TAG, config.getTag());

      // AMBARI-3672. Only consider Config groups for override tags
      // tags -> (configGroupId, versionTag)
      if (hostConfigOverrides != null) {
        HostConfig hostConfig = hostConfigOverrides.get(config.getType());
        if (hostConfig != null) {
          for (Entry<Long, String> tagEntry : hostConfig
              .getConfigGroupOverrides().entrySet()) {
            tags.put(tagEntry.getKey().toString(), tagEntry.getValue());
          }
        }
      }

      resolved.put(type, tags);
    }

    return resolved;
  }

  /**
   * Get all config properties for a cluster given a set of configType to
   * versionTags map. This helper method merges all the override tags with a
   * the properties from parent cluster config properties
   *
   * @param cluster
   * @param desiredTags
   * @return {type : {key, value}}
   */
  public Map<String, Map<String, String>> getEffectiveConfigProperties(
    Cluster cluster, Map<String, Map<String, String>> desiredTags) {

    Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();

    if (desiredTags != null) {
      for (Entry<String, Map<String, String>> entry : desiredTags.entrySet()) {
        String type = entry.getKey();
        Map<String, String> propertyMap = properties.get(type);
        if (propertyMap == null) {
          propertyMap = new HashMap<String, String>();
        }

        Map<String, String> tags = new HashMap<String, String>(entry.getValue());
        String clusterTag = tags.get(CLUSTER_DEFAULT_TAG);

        // Overrides is only supported if the config type exists at cluster
        // level
        if (clusterTag != null) {
          Config config = cluster.getConfig(type, clusterTag);
          if (config != null) {
            propertyMap.putAll(config.getProperties());
          }
          tags.remove(CLUSTER_DEFAULT_TAG);
          // Now merge overrides
          for (Entry<String, String> overrideEntry : tags.entrySet()) {
            Config overrideConfig = cluster.getConfig(type,
              overrideEntry.getValue());

            if (overrideConfig != null) {
              propertyMap = getMergedConfig(propertyMap, overrideConfig.getProperties());
            }
          }
        }
        properties.put(type, propertyMap);
      }
    }

    return properties;
  }

  /**
   * Get all config attributes for a cluster given a set of configType to
   * versionTags map. This helper method merges all the override tags with a
   * the attributes from parent cluster config properties
   *
   * @param cluster
   * @param desiredTags
   * @return {type : {attribute : {property, attributeValue}}
   */
  public Map<String, Map<String, Map<String, String>>> getEffectiveConfigAttributes(
      Cluster cluster, Map<String, Map<String, String>> desiredTags) {

    Map<String, Map<String, Map<String, String>>> attributes = new HashMap<String, Map<String, Map<String, String>>>();

    if (desiredTags != null) {
      for (Entry<String, Map<String, String>> entry : desiredTags.entrySet()) {

        String type = entry.getKey();
        Map<String, Map<String, String>> attributesMap = null;

        Map<String, String> tags = new HashMap<String, String>(entry.getValue());
        String clusterTag = tags.get(CLUSTER_DEFAULT_TAG);

        if (clusterTag != null) {
          Config config = cluster.getConfig(type, clusterTag);
          if (config != null) {
            attributesMap = new TreeMap<String, Map<String, String>>();
            cloneAttributesMap(config.getPropertiesAttributes(), attributesMap);
          }
          tags.remove(CLUSTER_DEFAULT_TAG);
        }
        for (Entry<String, String> overrideEntry : tags.entrySet()) {
          Config overrideConfig = cluster.getConfig(type,
              overrideEntry.getValue());
          overrideAttributes(overrideConfig, attributesMap);
        }
        if (attributesMap != null) {
          attributes.put(type, attributesMap);
        }
      }
    }

    return attributes;
  }

  /**
   * Merge override with original, if original property doesn't exist,
   * add it to the properties
   *
   * @param persistedClusterConfig
   * @param override
   * @return
   */
  public Map<String, String> getMergedConfig(Map<String,
      String> persistedClusterConfig, Map<String, String> override) {

    Map<String, String> finalConfig = new HashMap<String, String>(persistedClusterConfig);

    if (override != null && override.size() > 0) {
      for (Entry<String, String> entry : override.entrySet()) {
        Boolean deleted = 0 == entry.getKey().indexOf(DELETED);
        String nameToUse = deleted ?
            entry.getKey().substring(DELETED.length()) : entry.getKey();
        if (finalConfig.containsKey(nameToUse)) {
          finalConfig.remove(nameToUse);
        }
        if (!deleted) {
          finalConfig.put(nameToUse, entry.getValue());
        }
      }
    }

    return finalConfig;
  }

  /**
   * Merge override attributes with original ones.
   * If overrideConfig#getPropertiesAttributes does not contain occurrence of override for any of
   * properties from overrideConfig#getProperties then persisted attribute should be removed.
   */
  public Map<String, Map<String, String>> overrideAttributes(Config overrideConfig,
                                                             Map<String, Map<String, String>> persistedAttributes) {
    if (overrideConfig != null && persistedAttributes != null) {
      Map<String, Map<String, String>> overrideAttributes = overrideConfig.getPropertiesAttributes();
      if (overrideAttributes != null) {
        cloneAttributesMap(overrideAttributes, persistedAttributes);
        Map<String, String> overrideProperties = overrideConfig.getProperties();
        if (overrideProperties != null) {
          Set<String> overriddenProperties = overrideProperties.keySet();
          for (String overriddenProperty : overriddenProperties) {
            for (Entry<String, Map<String, String>> persistedAttribute : persistedAttributes.entrySet()) {
              String attributeName = persistedAttribute.getKey();
              Map<String, String> persistedAttributeValues = persistedAttribute.getValue();
              Map<String, String> overrideAttributeValues = overrideAttributes.get(attributeName);
              if (overrideAttributeValues == null || !overrideAttributeValues.containsKey(overriddenProperty)) {
                persistedAttributeValues.remove(overriddenProperty);
              }
            }
          }
        }
      }
    }
    return persistedAttributes;
  }

  public void cloneAttributesMap(Map<String, Map<String, String>> sourceAttributesMap,
                                 Map<String, Map<String, String>> targetAttributesMap) {
    if (sourceAttributesMap != null && targetAttributesMap != null) {
      for (Entry<String, Map<String, String>> attributesEntry : sourceAttributesMap.entrySet()) {
        String attributeName = attributesEntry.getKey();
        if (!targetAttributesMap.containsKey(attributeName)) {
          targetAttributesMap.put(attributeName, new TreeMap<String, String>());
        }
        for (Entry<String, String> attributesValue : attributesEntry.getValue().entrySet()) {
          targetAttributesMap.get(attributeName).put(attributesValue.getKey(), attributesValue.getValue());
        }
      }
    }
  }

  public void applyCustomConfig(Map<String, Map<String, String>> configurations,
      String type, String name, String value, Boolean deleted) {
    if (!configurations.containsKey(type)) {
      configurations.put(type, new HashMap<String, String>());
    }
    String nameToUse = deleted ? DELETED + name : name;
    Map<String, String> properties = configurations.get(type);
    if (properties.containsKey(nameToUse)) {
      properties.remove(nameToUse);
    }
    properties.put(nameToUse, value);
  }

  /**
   * The purpose of this method is to determine if a {@link ServiceComponentHost}'s
   * known actual configs are different than what is set on the cluster (the desired).
   * The following logic is applied:
   * <ul>
   *   <li>Desired type does not exist on the SCH (actual)
   *     <ul>
   *       <li>Type does not exist on the stack: <code>false</code></li>
   *       <li>Type exists on the stack: <code>true</code> if the config key is on the stack.
   *         otherwise <code>false</code></li>
   *     </ul>
   *   </li>
   *   <li> Desired type exists for the SCH
   *     <ul>
   *       <li>Desired tags already set for the SCH (actual): <code>false</code></li>
   *       <li>Desired tags DO NOT match SCH: <code>true</code> if the changed keys
   *         exist on the stack, otherwise <code>false</code></li>
   *     </ul>
   *   </li>
   * </ul>
   * @param @ServiceComponentHost
   * @return <code>true</code> if the actual configs are stale
   */
  public boolean isStaleConfigs(ServiceComponentHost sch) throws AmbariException {
    Boolean stale  = null;

    if (STALE_CONFIGS_CACHE_ENABLED) {
      stale = staleConfigsCache.getIfPresent(sch);
    }

    if (stale == null) {
      stale = calculateIsStaleConfigs(sch);
      staleConfigsCache.put(sch, stale);
    }
    return stale;
  }

  /**
   * Invalidates cached isStale values for hostname
   * @param hostname
   */
  public void invalidateStaleConfigsCache(String hostname) {
    try {
      for (Cluster cluster : clusters.getClustersForHost(hostname)) {
        for (ServiceComponentHost sch : cluster.getServiceComponentHosts(hostname)) {
          invalidateStaleConfigsCache(sch);
        }
      }
    } catch (AmbariException e) {
      LOG.warn("Unable to find clusters for host " + hostname);
    }
  }

  /**
   * Invalidates isStale cache
   */
  public void invalidateStaleConfigsCache() {
    staleConfigsCache.invalidateAll();
  }

  /**
   * Invalidates cached isStale value for sch
   * @param sch
   */
  public void invalidateStaleConfigsCache(ServiceComponentHost sch) {
    staleConfigsCache.invalidate(sch);
  }
  
  /**
   * Remove configs by type
   * @param type config Type
   */
  @Transactional
  public void removeConfigsByType(Cluster cluster, String type) {
    Set<String> globalVersions = cluster.getConfigsByType(type).keySet();
    
    for(String version:globalVersions) {
      ClusterConfigEntity clusterConfigEntity = clusterDAO.findConfig
        (cluster.getClusterId(), type, version);
      
      clusterDAO.removeConfig(clusterConfigEntity);
    }
  }
  
  /**
   * Gets all the config dictionary where property with the given name is present in stack definitions
   * @param stackId
   * @param propertyName
   */
  public Set<String> findConfigTypesByPropertyName(StackId stackId, String propertyName, String clusterName) throws AmbariException {
    StackInfo stack = ambariMetaInfo.getStack(stackId.getStackName(),
        stackId.getStackVersion());
    
    Set<String> result = new HashSet<String>();

    for(Service service : clusters.getCluster(clusterName).getServices().values()) {
      Set<PropertyInfo> stackProperties = ambariMetaInfo.getServiceProperties(stack.getName(), stack.getVersion(), service.getName());
      Set<PropertyInfo> stackLevelProperties = ambariMetaInfo.getStackProperties(stack.getName(), stack.getVersion());
      stackProperties.addAll(stackLevelProperties);
      
      for (PropertyInfo stackProperty : stackProperties) {
        if(stackProperty.getName().equals(propertyName)) {
          String configType = fileNameToConfigType(stackProperty.getFilename());
          
          result.add(configType);
        }
      }
    }
    
    return result;
  }
  
  public Set<String> getPropertyValuesWithPropertyType(StackId stackId, PropertyType propertyType, Cluster cluster) throws AmbariException {
    StackInfo stack = ambariMetaInfo.getStack(stackId.getStackName(),
        stackId.getStackVersion());
    
    Set<String> result = new HashSet<String>();

    for(Service service : cluster.getServices().values()) {
      Set<PropertyInfo> serviceProperties = ambariMetaInfo.getServiceProperties(stack.getName(), stack.getVersion(), service.getName());
      for (PropertyInfo serviceProperty : serviceProperties) {
        if(serviceProperty.getPropertyTypes().contains(propertyType)) {
          String stackPropertyConfigType = fileNameToConfigType(serviceProperty.getFilename());
          try {
            result.add(cluster.getDesiredConfigByType(stackPropertyConfigType).getProperties().get(serviceProperty.getName()));
          } catch(Exception ex) {}
        }
      }
    }
    
    Set<PropertyInfo> stackProperties = ambariMetaInfo.getStackProperties(stack.getName(), stack.getVersion());
    
    for (PropertyInfo stackProperty : stackProperties) {
      if(stackProperty.getPropertyTypes().contains(propertyType)) {
        String stackPropertyConfigType = fileNameToConfigType(stackProperty.getFilename());
        result.add(cluster.getDesiredConfigByType(stackPropertyConfigType).getProperties().get(stackProperty.getName()));
      }
    }
    
    return result;
  }
  
  public String getPropertyValueFromStackDefinitions(Cluster cluster, String configType, String propertyName) throws AmbariException {
    StackId stackId = cluster.getCurrentStackVersion();
    StackInfo stack = ambariMetaInfo.getStack(stackId.getStackName(),
        stackId.getStackVersion());
    
    for(ServiceInfo serviceInfo:stack.getServices()) {
      Set<PropertyInfo> serviceProperties = ambariMetaInfo.getServiceProperties(stack.getName(), stack.getVersion(), serviceInfo.getName());
      Set<PropertyInfo> stackProperties = ambariMetaInfo.getStackProperties(stack.getName(), stack.getVersion());
      serviceProperties.addAll(stackProperties);
      
      for (PropertyInfo stackProperty : serviceProperties) {
        String stackPropertyConfigType = fileNameToConfigType(stackProperty.getFilename());
        
        if(stackProperty.getName().equals(propertyName) && stackPropertyConfigType.equals(configType)) {
          return stackProperty.getValue();
        }
      }
      
    }
    
    return null;
  }
  
  public ServiceInfo getPropertyOwnerService(Cluster cluster, String configType, String propertyName) throws AmbariException {
    StackId stackId = cluster.getCurrentStackVersion();
    StackInfo stack = ambariMetaInfo.getStack(stackId.getStackName(), stackId.getStackVersion());
    
    for(ServiceInfo serviceInfo:stack.getServices()) {     
      Set<PropertyInfo> serviceProperties = ambariMetaInfo.getServiceProperties(stack.getName(), stack.getVersion(), serviceInfo.getName());
      
      for (PropertyInfo stackProperty : serviceProperties) {
        String stackPropertyConfigType = fileNameToConfigType(stackProperty.getFilename());
        
        if(stackProperty.getName().equals(propertyName) && stackPropertyConfigType.equals(configType)) {
          return serviceInfo;
        }
      }
      
    }
    
    return null;
  }
  
  public Set<PropertyInfo> getServiceProperties(Cluster cluster, String serviceName) throws AmbariException {
    StackId stackId = cluster.getCurrentStackVersion();
    StackInfo stack = ambariMetaInfo.getStack(stackId.getStackName(), stackId.getStackVersion());
    
    return ambariMetaInfo.getServiceProperties(stack.getName(), stack.getVersion(), serviceName);
  }
  
  public Set<PropertyInfo> getStackProperties(Cluster cluster) throws AmbariException {
    StackId stackId = cluster.getCurrentStackVersion();
    StackInfo stack = ambariMetaInfo.getStack(stackId.getStackName(), stackId.getStackVersion());
    
    return ambariMetaInfo.getStackProperties(stack.getName(), stack.getVersion());
  }
  
  public void createConfigType(Cluster cluster, AmbariManagementController ambariManagementController, 
      String configType, Map<String, String> properties, String authName) throws AmbariException {
    String tag;
    if(cluster.getConfigsByType(configType) == null) {
      tag = "version1";
    } else {
      tag = "version" + System.currentTimeMillis();
    }
    
    ConfigurationRequest cr = new ConfigurationRequest();
    cr.setClusterName(cluster.getClusterName());
    cr.setVersionTag(tag);
    cr.setType(configType);
    cr.setProperties(properties);
    ambariManagementController.createConfiguration(cr);
    
    Config baseConfig = cluster.getConfig(cr.getType(), cr.getVersionTag());
    
    if (baseConfig != null) {
      cluster.addDesiredConfig(authName, Collections.singleton(baseConfig));
    }
  }
  
  /**
   * Since global configs are deprecated since 1.7.0, but still supported.
   * We should automatically map any globals used, to *-env dictionaries.
   *
   * @param configurations  map of configurations keyed by type
   */
  public void moveDeprecatedGlobals(StackId stackId, Map<String, Map<String, String>> configurations, String clusterName) {
    Map<String, String> globalConfigurations = new HashMap<String, String>();
    
    if(configurations.get(Configuration.GLOBAL_CONFIG_TAG) == null ||
        configurations.get(Configuration.GLOBAL_CONFIG_TAG).size() == 0)
      return;
  
    globalConfigurations.putAll(configurations.get(Configuration.GLOBAL_CONFIG_TAG));
    
    if(globalConfigurations!=null && globalConfigurations.size() != 0) {
      LOG.warn("Global configurations are deprecated, "
          + "please use *-env");
    }
    
    for(Map.Entry<String, String> property:globalConfigurations.entrySet()) {
      String propertyName = property.getKey();
      String propertyValue = property.getValue();
      
      Set<String> newConfigTypes = null;
      try{
        newConfigTypes = this.findConfigTypesByPropertyName(stackId, propertyName, clusterName);
      } catch(AmbariException e) {
        LOG.error("Exception while getting configurations from the stacks", e);
        return;
      }
      
      newConfigTypes.remove(Configuration.GLOBAL_CONFIG_TAG);
      
      String newConfigType = null;
      if(newConfigTypes.size() > 0) {
        newConfigType = newConfigTypes.iterator().next();
      } else {
        newConfigType = UpgradeCatalog170.getAdditionalMappingGlobalToEnv().get(propertyName);
      }
      
      if(newConfigType==null) {
        LOG.warn("Cannot find where to map " + propertyName + " from " + Configuration.GLOBAL_CONFIG_TAG +
            " (value="+propertyValue+")");
        continue;
      }
      
      LOG.info("Mapping config " + propertyName + " from " + Configuration.GLOBAL_CONFIG_TAG + 
          " to " + newConfigType +
          " (value="+propertyValue+")");
      
      configurations.get(Configuration.GLOBAL_CONFIG_TAG).remove(propertyName);
      
      if(!configurations.containsKey(newConfigType)) {
        configurations.put(newConfigType, new HashMap<String, String>());
      }
      configurations.get(newConfigType).put(propertyName, propertyValue);
    }
    
    if(configurations.get(Configuration.GLOBAL_CONFIG_TAG).size() == 0) {
      configurations.remove(Configuration.GLOBAL_CONFIG_TAG);
    }
  }

  private boolean calculateIsStaleConfigs(ServiceComponentHost sch) throws AmbariException {

    if (sch.isRestartRequired()) {
      return true;
    }

    Map <String, HostConfig> actual = sch.getActualConfigs();
    if (null == actual || actual.isEmpty())
      return false;

    Cluster cluster = clusters.getClusterById(sch.getClusterId());
    StackId stackId = cluster.getDesiredStackVersion();
    
    Map<String, Map<String, String>> desired = getEffectiveDesiredTags(cluster,
        sch.getHostName());
    
    ServiceInfo serviceInfo = ambariMetaInfo.getService(stackId.getStackName(),
        stackId.getStackVersion(), sch.getServiceName());
    ComponentInfo componentInfo = serviceInfo.getComponentByName(sch.getServiceComponentName());
    // Configs are considered stale when:
    // - desired type DOES NOT exist in actual
    // --- desired type DOES NOT exist in stack: not_stale
    // --- desired type DOES exist in stack: check stack for any key: stale
    // - desired type DOES exist in actual
    // --- desired tags DO match actual tags: not_stale
    // --- desired tags DO NOT match actual tags
    // ---- merge values, determine changed keys, check stack: stale
    boolean stale = false;

    Iterator<Entry<String, Map<String, String>>> it = desired.entrySet().iterator();
    
    while (it.hasNext() && !stale) {
      Entry<String, Map<String, String>> desiredEntry = it.next();
      
      String type = desiredEntry.getKey();
      Map<String, String> tags = desiredEntry.getValue();
      
      if (!actual.containsKey(type)) {
        // desired is set, but actual is not
        if (!serviceInfo.hasConfigDependency(type)) {
          stale = componentInfo != null && componentInfo.hasConfigType(type);
        } else if (type.equals(Configuration.GLOBAL_CONFIG_TAG)) {
          // find out if the keys are stale by first checking the target service,
          // then all services
          Collection<String> keys = mergeKeyNames(cluster, type, tags.values());
          
          if (serviceInfo.hasDependencyAndPropertyFor(type, keys) || !hasPropertyFor(stackId, type, keys)) {
            stale = true;
          }
        } else {
          stale = true;
        }
      } else {
        // desired and actual both define the type
        HostConfig hc = actual.get(type);
        Map<String, String> actualTags = buildTags(hc);

        if (!isTagChanged(tags, actualTags, hasGroupSpecificConfigsForType(cluster, sch.getHostName(), type))) {
          stale = false;
        } else if (type.equals(Configuration.GLOBAL_CONFIG_TAG)) {
          // tags are changed, need to find out what has changed,
          // and if it applies
          // to the service
          Collection<String> changed = findChangedKeys(cluster, type,
            tags.values(), actualTags.values());
          if (serviceInfo.hasDependencyAndPropertyFor(type, changed)) {
            stale = true;
          }
        } else {
          stale = serviceInfo.hasConfigDependency(type) || componentInfo.hasConfigType(type);
        }
      }
    }
    return stale;
  }

  /**
   * Determines if the hostname has group specific configs for the type specified
   *
   * @param cluster
   * @param hostname of the host to look for
   * @param type     the type to look for (e.g. flume-conf)
   * @return <code>true</code> if the hostname has group specific configuration for the type
   */
  private boolean hasGroupSpecificConfigsForType(Cluster cluster, String hostname, String type) {
    try {
      Map<Long, ConfigGroup> configGroups = cluster.getConfigGroupsByHostname(hostname);
      if (configGroups != null && !configGroups.isEmpty()) {
        for (ConfigGroup configGroup : configGroups.values()) {
          Config config = configGroup.getConfigurations().get(type);
          if (config != null) {
            return true;
          }
        }
      }
    } catch (AmbariException ambariException) {
      LOG.warn("Could not determine group configuration for host. Details: " + ambariException.getMessage());
    }
    return false;
  }

  /**
   * @return <code>true</code> if any service on the stack defines a property
   * for the type.
   */
  private boolean hasPropertyFor(StackId stack, String type,
      Collection<String> keys) throws AmbariException {

    for (ServiceInfo svc : ambariMetaInfo.getServices(stack.getStackName(),
        stack.getStackVersion()).values()) {
      
      if (svc.hasDependencyAndPropertyFor(type, keys))
        return true;
      
    }
    
    return false;
  }
  
  /**
   * @return the keys that have changed values
   */
  private Collection<String> findChangedKeys(Cluster cluster, String type,
      Collection<String> desiredTags, Collection<String> actualTags) {
    
    Map<String, String> desiredValues = new HashMap<String, String>();
    Map<String, String> actualValues = new HashMap<String, String>();
    
    for (String tag : desiredTags) {
      Config config = cluster.getConfig(type, tag);
      if (null != config)
        desiredValues.putAll(config.getProperties());
    }
    
    for (String tag : actualTags) {
      Config config = cluster.getConfig(type, tag);
      if (null != config)
        actualValues.putAll(config.getProperties());
    }
    
    List<String> keys = new ArrayList<String>();
    
    for (Entry<String, String> entry : desiredValues.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      
      if (!actualValues.containsKey(key))
        keys.add(key);
      else if (!actualValues.get(key).equals(value))
        keys.add(key);
    }
    
    return keys;
  }
  
  /**
   * @return the map of tags for a desired config
   */
  private Map<String, String> buildTags(HostConfig hc) {
    Map<String, String> map = new LinkedHashMap<String, String>();
    map.put(CLUSTER_DEFAULT_TAG, hc.getDefaultVersionTag());
    if (hc.getConfigGroupOverrides() != null) {
      for (Entry<Long, String> entry : hc.getConfigGroupOverrides().entrySet()) {
        map.put(entry.getKey().toString(), entry.getValue());
      }
    }
    return map;
  }
  
  /**
   * @return true if the tags are different in any way, even if not-specified
   */
  private boolean isTagChanged(Map<String, String> desiredTags, Map<String, String> actualTags, boolean groupSpecificConfigs) {
    if (!actualTags.get(CLUSTER_DEFAULT_TAG).equals(desiredTags.get(CLUSTER_DEFAULT_TAG)) && !groupSpecificConfigs)
      return true;

    // if the host has group specific configs for type we should ignore the cluster level configs and compare specifics
    if (groupSpecificConfigs) {
      actualTags.remove(CLUSTER_DEFAULT_TAG);
      desiredTags.remove(CLUSTER_DEFAULT_TAG);
    }

    Set<String> desiredSet = new HashSet<String>(desiredTags.values());
    Set<String> actualSet = new HashSet<String>(actualTags.values());

    // Both desired and actual should be exactly the same
    return !desiredSet.equals(actualSet);
  }

  /**
   * @return  the list of combined config property names
   */
  private Collection<String> mergeKeyNames(Cluster cluster, String type, Collection<String> tags) {
    Set<String> names = new HashSet<String>();
    
    for (String tag : tags) {
      Config config = cluster.getConfig(type, tag);
      if (null != config) {
        names.addAll(config.getProperties().keySet());
      }
    }
    
    return names;
  }


  public static String fileNameToConfigType(String filename) {
    int extIndex = filename.indexOf(AmbariMetaInfo.SERVICE_CONFIG_FILE_NAME_POSTFIX);
    return filename.substring(0, extIndex);
  }


}
