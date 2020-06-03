/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.feature.apiregions.impl;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.osgi.framework.Bundle;
import org.osgi.framework.Version;
import org.osgi.framework.hooks.resolver.ResolverHook;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;

class ResolverHookImpl implements ResolverHook {

    final RegionConfiguration configuration;

    ResolverHookImpl(RegionConfiguration cfg) {
        this.configuration = cfg;
    }

    @Override
    public void filterResolvable(Collection<BundleRevision> candidates) {
        // Nothing to do
    }

    @Override
    public void filterSingletonCollisions(BundleCapability singleton, Collection<BundleCapability> collisionCandidates) {
        // Nothing to do
    }

    @Override
    public void filterMatches(BundleRequirement requirement, Collection<BundleCapability> candidates) {
        // Filtering is only on package resolution. Any other kind of resolution is not limited
        if (!PackageNamespace.PACKAGE_NAMESPACE.equals(requirement.getNamespace()))
            return;

        Bundle reqBundle = requirement.getRevision().getBundle();
        long reqBundleID = reqBundle.getBundleId();

        Set<String> reqRegions = new HashSet<>(this.configuration.getDefaultRegions());
        Set<String> reqFeatures = getFeaturesForBundle(reqBundle);
        for (String feature : reqFeatures) {
            Set<String> fr = this.configuration.getFeatureRegionMap().get(feature);
            if (fr != null) {
                reqRegions.addAll(fr);
            }
        }

        Map<BundleCapability, String> coveredCaps = new HashMap<>();
        Map<BundleCapability, String> bcFeatureMap = new HashMap<>();
        String packageName = null;
        nextCapability:
        for (BundleCapability bc : candidates) {
            BundleRevision rev = bc.getRevision();

            Bundle capBundle = rev.getBundle();
            long capBundleID = capBundle.getBundleId();
            if (capBundleID == 0) {
                // always allow capability from the system bundle
                coveredCaps.put(bc, null); // null value means same bundle, same feature or system bundlee
                continue nextCapability;
            }

            if (capBundleID == reqBundleID) {
                // always allow capability from same bundle
                coveredCaps.put(bc, null); // null value means same bundle, same feature or system bundle
                continue nextCapability;
            }

            Set<String> capFeatures = getFeaturesForBundle(capBundle);
            if (capFeatures.isEmpty()) {
                // Capability is not in any feature, everyone can access
                coveredCaps.put(bc, RegionConstants.GLOBAL_REGION);
                continue nextCapability;
            }

            if (capFeatures.isEmpty())
                capFeatures = Collections.singleton(null);

            for (String capFeat : capFeatures) {
                if (capFeat == null) {
                    // everyone can access capability not coming from a feature
                    coveredCaps.put(bc, RegionConstants.GLOBAL_REGION);
                    continue nextCapability;
                }

                if (reqFeatures.contains(capFeat)) {
                    // Within a single feature everything can wire to everything else
                    coveredCaps.put(bc, null); // null value means same bundle, same feature or system bundle
                    continue nextCapability;
                }

                Set<String> capRegions = this.configuration.getFeatureRegionMap().get(capFeat);
                if (capRegions == null || capRegions.size() == 0) {
                    // If the feature hosting the capability has no regions defined, everyone can access
                    coveredCaps.put(bc, RegionConstants.GLOBAL_REGION);
                    continue nextCapability;
                }
                bcFeatureMap.put(bc, capFeat);

                List<String> sharedRegions = new ArrayList<>(reqRegions);
                sharedRegions.retainAll(capRegions);

                Object pkg = bc.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
                if (pkg instanceof String) {
                    packageName = (String) pkg;

                    // Look at specific regions first as they take precedence over the global region
                    for (String region : sharedRegions) {
                        Set<String> regionPackages = this.configuration.getRegionPackageMap().get(region);
                        if (regionPackages != null && regionPackages.contains(packageName)) {
                            // If the export is in a region that the feature is also in, then allow
                            coveredCaps.put(bc, region);
                            continue nextCapability;
                        }
                    }

                    // Now check the global region
                    Set<String> globalPackages = this.configuration.getRegionPackageMap().get(RegionConstants.GLOBAL_REGION);
                    if (globalPackages != null && globalPackages.contains(packageName)) {
                        // If the export is in the global region everyone can access
                        coveredCaps.put(bc, RegionConstants.GLOBAL_REGION);
                        continue nextCapability;
                    }
                }
            }
        }

        pruneCoveredCaps(reqRegions, coveredCaps);

        List<BundleCapability> removedCandidates = new ArrayList<>(candidates);
        // Remove any capabilities that are not covered
        candidates.retainAll(coveredCaps.keySet());

        Level logLevel;
        if (candidates.isEmpty()) {
            logLevel = Level.WARNING;
        } else {
            logLevel = Level.INFO;
        }
        removedCandidates.removeAll(candidates);

        if (!removedCandidates.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (BundleCapability bc : removedCandidates) {
                if (first)
                    first = false;
                else
                    sb.append(", ");

                String capFeat = bcFeatureMap.get(bc);
                sb.append(bc.toString());
                sb.append("[Regions: ");
                sb.append(getRegionsForPackage(packageName, capFeat));
                sb.append(", Feature: ");
                sb.append(capFeat);
                sb.append("]");
            }

            Activator.LOG.log(logLevel,
                    "API-Regions removed candidates {0} for requirement {1} as the requirement is in the following regions: {2} and in feature: {3}",
                    new Object[] {sb, requirement, reqRegions, reqFeatures});
        }
    }

    /*
     * If there are multiple choices of capabilities and some of the capabilities are in the global
     * region while others are in another named region, take out the capabilities from the global
     * region so that the requirement gets wired to the more 'specifc' one than the global one.
     * Capabilities from bundle 0 (the system bundle), the same bundle as the requirer and from the
     * same feature as the requirer should always be kept. These are marked in the capMap with a
     * {@code null} region value.
     */
    private void pruneCoveredCaps(Set<String> reqRegions, Map<BundleCapability,String> capMap) {
        Set<String> reqNonGlobalRegions = new HashSet<>(reqRegions);
        reqNonGlobalRegions.remove(RegionConstants.GLOBAL_REGION);

        if (capMap.size() <= 1) {
            // Shortcut: there is only 0 or 1 capability, nothing to do
            return;
        }

        if (reqRegions.size() == 0
                || Collections.singleton(RegionConstants.GLOBAL_REGION).equals(reqRegions)) {
            // No regions (other than global) for the requirement: do nothing
            return;
        }

        List<BundleCapability> specificCaps = new ArrayList<>();
        for (Iterator<Map.Entry<BundleCapability,String>> it = capMap.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BundleCapability,String> entry = it.next();

            String capRegion = entry.getValue();
            if (capRegion == null) {
                // This one came from the same bundle, the same feature or bundle 0 -> always allow
                specificCaps.add(entry.getKey());
                continue;
            }

            if (reqNonGlobalRegions.contains(capRegion)) {
                // the requirement has the region from the capability
                specificCaps.add(entry.getKey());
            }
        }

        if (specificCaps.size() == 0) {
            // There are no capabilities that are either in the same bundle, same feature or overlapping specific
            // feature. We should just allow all, including the global region
            return;
        }

        // There are specific capabilities, therefore we should remove the Global region is any from the capabilities
        // We have collected the capabilities we want to keep in specificCaps
        for (Iterator<BundleCapability> it = capMap.keySet().iterator(); it.hasNext(); ) {
            BundleCapability cap = it.next();
            if (!specificCaps.contains(cap)) {
                it.remove();
                Activator.LOG.log(Level.INFO, "Removing candidate {0} which is in region {1} as more specific candidates are available in regions {2}",
                        new Object[] {
                                cap, capMap.get(cap),
                                specificCaps.stream().map(c -> "" + c + " region " + capMap.get(c)).collect(Collectors.joining("/"))
                        });
            }
        }
    }

    Set<String> getFeaturesForBundle(Bundle bundle) {
        // Look up the bsn and bundle version initially associated with the location. If the bundle
        // for the specified location was later updated, the initial bsn+version is still used to look up the
        // api regions configuration
        Map.Entry<String, Version> bsnVer = this.configuration.getBundleLocationConfigMap()
                .computeIfAbsent(bundle.getLocation(), l -> new AbstractMap.SimpleEntry<>(
                        bundle.getSymbolicName(), bundle.getVersion()));

        return getFeaturesForBundleFromConfig(bsnVer.getKey(), bsnVer.getValue());
    }

    private Set<String> getFeaturesForBundleFromConfig(String bundleName, Version bundleVersion) {
        Set<String> newSet = new HashSet<>();
        List<String> aids = this.configuration.getBsnVerMap().get(
                new AbstractMap.SimpleEntry<String, Version>(bundleName, bundleVersion));
        if (aids != null) {
            for (String aid : aids) {
                Set<String> fid = this.configuration.getBundleFeatureMap().get(aid);
                if (fid != null)
                    newSet.addAll(fid);
            }
        }

        return Collections.unmodifiableSet(newSet);
    }

    List<String> getRegionsForPackage(String packageName, String feature) {
        if (packageName == null)
            return Collections.emptyList();

        Set<String> regions = this.configuration.getFeatureRegionMap().get(feature);
        if (regions == null)
            return Collections.emptyList();

        List<String> res = new ArrayList<>();
        for (String region : regions) {
            Set<String> packages = this.configuration.getRegionPackageMap().get(region);
            if (packages == null)
                continue;

            if (packages.contains(packageName)) {
                res.add(region);
            }
        }
        return res;
    }

    @Override
    public void end() {
        // Nothing to do
    }
}
