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
package org.apache.sling.feature.maven.mojos;

import java.util.ArrayList;
import java.util.List;

import org.apache.maven.model.Dependency;

public class Repository extends FeatureSelectionConfig {

    private List<Dependency> embedArtifacts = new ArrayList<>();

    /**
     * The directory for the repository to store. This directory is relative to the
     * build directory.
     */
    public String repositoryDir = "artifacts";

    public void setEmbedArtifact(final Dependency dep) {
        this.embedArtifacts.add(dep);
    }

    public List<Dependency> getEmbedArtifacts() {
        return this.embedArtifacts;
    }

    @Override
    public String toString() {
        return "Repository [filesIncludes=" + getFilesIncludes() + ", filesExcludes=" + getFilesExcludes()
                + ", includeArtifacts=" + getIncludeArtifacts() + ", includeClassifiers=" + getIncludeClassifiers()
                + ", embedArtifacts="
                + embedArtifacts + ", repositoryDir="
                + repositoryDir + "]";
    }
}