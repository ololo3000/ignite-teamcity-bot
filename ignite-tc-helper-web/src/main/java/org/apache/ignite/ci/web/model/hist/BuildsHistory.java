/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.ci.web.model.hist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.servlet.ServletContext;
import org.apache.ignite.ci.IAnalyticsEnabledTeamcity;
import org.apache.ignite.ci.ITcHelper;
import org.apache.ignite.ci.tcbot.chain.BuildChainProcessor;
import org.apache.ignite.ci.tcmodel.result.Build;
import org.apache.ignite.ci.tcmodel.result.tests.TestOccurrence;
import org.apache.ignite.ci.tcmodel.result.tests.TestOccurrences;
import org.apache.ignite.ci.user.ICredentialsProv;
import org.apache.ignite.ci.web.CtxListener;
import org.apache.ignite.ci.web.model.current.BuildStatisticsSummary;
import org.apache.ignite.ci.web.rest.exception.ServiceUnauthorizedException;
import org.apache.ignite.ci.web.rest.parms.FullQueryParams;

import static com.google.common.base.Strings.isNullOrEmpty;
import static org.apache.ignite.ci.web.rest.build.GetBuildTestFailures.BUILDS_STATISTICS_SUMMARY_CACHE_NAME;

/**
 * Builds History: includes statistic for every build and merged failed unmuted tests in specified time interval.
 */
public class BuildsHistory {
    /** */
    private String srvId;

    /** */
    private String projectId;

    /** */
    private String buildTypeId;

    /** */
    private String branchName;

    /** */
    private Date sinceDateFilter;

    /** */
    private Date untilDateFilter;

    /** */
    private Map<String, Set<String>> mergedTestsBySuites = new HashMap<>();

    /** */
    public List<BuildStatisticsSummary> buildsStatistics = new ArrayList<>();

    /** */
    public String mergedTestsJson;

    /** */
    public void initialize(ICredentialsProv prov, ServletContext context) {
        if (!prov.hasAccess(srvId))
            throw ServiceUnauthorizedException.noCreds(srvId);

        ITcHelper tcHelper = CtxListener.getTcHelper(context);

        IAnalyticsEnabledTeamcity teamcity = tcHelper.server(srvId, prov);

        int[] finishedBuildsIds = teamcity.getBuildNumbersFromHistory(buildTypeId, branchName,
            sinceDateFilter, untilDateFilter);

        initBuildsStatistics(teamcity, prov, context, finishedBuildsIds);

        initBuildsMergedFailedTests(teamcity, finishedBuildsIds);
    }

    /** */
    private void initBuildsStatistics(IAnalyticsEnabledTeamcity teamcity, ICredentialsProv prov,
        ServletContext context, int[] buildIds) {
        for (int buildId : buildIds) {
            FullQueryParams buildParams = new FullQueryParams();

            buildParams.setBuildId(buildId);
            buildParams.setBranch(branchName);
            buildParams.setServerId(srvId);

            BuildStatisticsSummary buildsStatistic = CtxListener.getBackgroundUpdater(context).get(
                BUILDS_STATISTICS_SUMMARY_CACHE_NAME, prov, buildParams,
                (k) -> {
                    BuildStatisticsSummary stat = new BuildStatisticsSummary(buildId);

                    stat.initialize(teamcity);

                    return stat;
                }, false);

            if (buildsStatistic != null && !buildsStatistic.isFakeStub)
                buildsStatistics.add(buildsStatistic);
        }
    }

    /** */
    private void initBuildsMergedFailedTests(IAnalyticsEnabledTeamcity teamcity, int[] buildIds) {
        for (int buildId : buildIds) {
            Build build = teamcity.getBuild(teamcity.getBuildHrefById(buildId));

            TestOccurrences testOccurrences = teamcity.getFailedUnmutedTests(build.testOccurrences.href,
                build.testOccurrences.failed, BuildChainProcessor.normalizeBranch(build.branchName));

            for (TestOccurrence testOccurrence : testOccurrences.getTests()) {
                String testName = testOccurrence.getName();

                build = teamcity.getBuild(teamcity.getBuildHrefById(testOccurrence.getBuildId()));

                Set<String> tests = mergedTestsBySuites.computeIfAbsent(build.buildTypeId,
                    k -> new HashSet<>());

                if (!tests.add(testName))
                    continue;

                FullQueryParams key = new FullQueryParams();

                key.setServerId(srvId);
                key.setProjectId(projectId);
                key.setTestName(testOccurrence.getName());
                key.setSuiteId(build.buildTypeId);

                teamcity.getTestRef(key);
            }
        }

        ObjectMapper objectMapper = new ObjectMapper();

        try {
            mergedTestsJson = objectMapper.writeValueAsString(mergedTestsBySuites);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /** */
    public BuildsHistory(Builder builder) {
        this.srvId = builder.srvId;
        this.buildTypeId = builder.buildTypeId;
        this.branchName = builder.branchName;
        this.sinceDateFilter = builder.sinceDate;
        this.untilDateFilter = builder.untilDate;
        this.projectId = builder.projectId;
    }

    /** */
    public static class Builder {
        /** */
        private String projectId = "IgniteTests24Java8";

        /** */
        private String srvId = "apache";

        /** */
        private String buildTypeId = "IgniteTests24Java8_RunAll";

        /** */
        private String branchName = "refs/heads/master";

        /** */
        private Date sinceDate = null;

        /** */
        private Date untilDate = null;

        /** */
        private DateFormat dateFormat = new SimpleDateFormat("ddMMyyyyHHmmss");

        /** */
        public Builder server(String srvId) {
            if (!isNullOrEmpty(srvId))
                this.srvId = srvId;

            return this;
        }

        /** */
        public Builder buildType(String buildType) {
            if (!isNullOrEmpty(buildType))
                this.buildTypeId = buildType;

            return this;
        }

        /** */
        public Builder project(String projectId) {
            if (!isNullOrEmpty(projectId))
                this.projectId = projectId;

            return this;
        }

        /** */
        public Builder branch(String branchName) {
            if (!isNullOrEmpty(branchName))
                this.branchName = branchName;

            return this;
        }

        /** */
        public Builder sinceDate(String sinceDate) throws ParseException {
            if (!isNullOrEmpty(sinceDate))
                this.sinceDate = dateFormat.parse(sinceDate);

            return this;
        }

        /** */
        public Builder untilDate(String untilDate) throws ParseException {
            if (!isNullOrEmpty(untilDate))
                this.untilDate = dateFormat.parse(untilDate);

            return this;
        }

        /** */
        public BuildsHistory build() {
            return new BuildsHistory(this);
        }
    }
}