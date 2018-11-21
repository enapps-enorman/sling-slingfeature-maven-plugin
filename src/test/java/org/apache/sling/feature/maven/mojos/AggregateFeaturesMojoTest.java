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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.DefaultMavenProjectHelper;
import org.apache.maven.project.MavenProject;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.maven.FeatureConstants;
import org.apache.sling.feature.maven.Preprocessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


@SuppressWarnings("deprecation")
public class AggregateFeaturesMojoTest {
    private Path tempDir;
    private static Map<String, ArtifactId> pluginCallbacks;

    public static final String FEATURE_PROCESSED_LOCATION = "/features/processed";

    @Before
    public void setup() throws Exception {
        tempDir = Files.createTempDirectory(getClass().getSimpleName());
        pluginCallbacks = new HashMap<>();
    }

    @After
    public void tearDown() throws Exception {
        // Delete the temp dir again
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
    }

    public static void addPluginCallback(String plugin, ArtifactId artifactId) {
        pluginCallbacks.put(plugin, artifactId);
    }

    @Test
    public void testAggregateFeaturesFromDirectory() throws Exception {
        File featuresDir = new File(
                getClass().getResource("/aggregate-features/dir2").getFile());
        // read features
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : featuresDir.listFiles((d,f) -> f.endsWith(".json"))) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        Aggregate fc = new Aggregate();
        fc.setFilesInclude("*.json");

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        Artifact parentArtifact = createMockArtifact();
        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getGroupId()).thenReturn("org.foo");
        Mockito.when(mockProj.getArtifactId()).thenReturn("org.foo.bar");
        Mockito.when(mockProj.getVersion()).thenReturn("1.2.3-SNAPSHOT");
        Mockito.when(mockProj.getArtifact()).thenReturn(parentArtifact);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/rawmain.json-cache"))
            .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Preprocessor.class.getName())).thenReturn(Boolean.TRUE);

        AggregateFeaturesMojo af = new AggregateFeaturesMojo();
        fc.classifier = "aggregated";
        af.aggregates = Collections.singletonList(fc);
        af.project = mockProj;
        af.projectHelper = new DefaultMavenProjectHelper();
        af.features = featuresDir;
        af.execute();

        Feature genFeat = featureMap.get(":aggregate:aggregated");
        assertNotNull(genFeat);
        ArtifactId id = genFeat.getId();

        assertEquals("org.foo", id.getGroupId());
        assertEquals("org.foo.bar", id.getArtifactId());
        assertEquals("1.2.3-SNAPSHOT", id.getVersion());
        assertEquals(FeatureConstants.PACKAGING_FEATURE, id.getType());
        assertEquals("aggregated", id.getClassifier());

        Set<ArtifactId> expectedBundles = new HashSet<>();
        expectedBundles.add(
                new ArtifactId("org.apache.aries", "org.apache.aries.util", "1.1.3", null, null));
        expectedBundles.add(
                new ArtifactId("org.apache.sling", "someotherbundle", "1", null, null));
        Set<ArtifactId> actualBundles = new HashSet<>();
        for (org.apache.sling.feature.Artifact art : genFeat.getBundles()) {
            actualBundles.add(art.getId());
        }
        assertEquals(expectedBundles, actualBundles);

        Map<String, Dictionary<String, Object>> expectedConfigs = new HashMap<>();
        expectedConfigs.put("some.pid", new Hashtable<>(Collections.singletonMap("x", "y")));
        Dictionary<String, Object> dict = new Hashtable<>();
        dict.put("foo", 123L);
        dict.put("bar", Boolean.TRUE);
        expectedConfigs.put("another.pid", dict);

        Map<String, Dictionary<String, Object>> actualConfigs = new HashMap<>();
        for (org.apache.sling.feature.Configuration conf : genFeat.getConfigurations()) {
            actualConfigs.put(conf.getPid(), conf.getProperties());
        }
        assertEquals(expectedConfigs, actualConfigs);
    }

    @Test
    public void testAggregateFeaturesFromDirectoryWithIncludesExcludes() throws Exception {
        File featuresDir = new File(
                getClass().getResource("/aggregate-features/dir").getFile());
        // read features
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : featuresDir.listFiles((d,f) -> f.endsWith(".json"))) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        Aggregate fc = new Aggregate();
        fc.setFilesInclude("*.json");
        fc.setFilesInclude("*.foobar");
        fc.setFilesExclude("*_v*");
        fc.setFilesExclude("test_w.json");

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        Artifact parentArtifact = createMockArtifact();
        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getGroupId()).thenReturn("org.foo");
        Mockito.when(mockProj.getArtifactId()).thenReturn("org.foo.bar");
        Mockito.when(mockProj.getVersion()).thenReturn("1.2.3-SNAPSHOT");
        Mockito.when(mockProj.getArtifact()).thenReturn(parentArtifact);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/rawmain.json-cache"))
            .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Preprocessor.class.getName())).thenReturn(Boolean.TRUE);

        AggregateFeaturesMojo af = new AggregateFeaturesMojo();
        fc.classifier = "aggregated";
        af.aggregates = Collections.singletonList(fc);
        af.project = mockProj;
        af.projectHelper = new DefaultMavenProjectHelper();
        af.features = featuresDir;

        af.execute();

        Feature genFeat = featureMap.get(":aggregate:aggregated");
        assertNotNull(genFeat);
        ArtifactId id = genFeat.getId();

        assertEquals("org.foo", id.getGroupId());
        assertEquals("org.foo.bar", id.getArtifactId());
        assertEquals("1.2.3-SNAPSHOT", id.getVersion());
        assertEquals(FeatureConstants.PACKAGING_FEATURE, id.getType());
        assertEquals("aggregated", id.getClassifier());

        int numBundlesFound = 0;
        for (org.apache.sling.feature.Artifact art : genFeat.getBundles()) {
            numBundlesFound++;

            ArtifactId expectedBundleCoords =
                    new ArtifactId("org.apache.aries", "org.apache.aries.util", "1.1.3", null, null);
            assertEquals(expectedBundleCoords, art.getId());
        }
        assertEquals("Expected only one bundle", 1, numBundlesFound);

        Map<String, Dictionary<String, Object>> expectedConfigs = new HashMap<>();
        expectedConfigs.put("some.pid", new Hashtable<>(Collections.singletonMap("x", "y")));
        Dictionary<String, Object> dict = new Hashtable<>();
        dict.put("foo", 123L);
        dict.put("bar", Boolean.TRUE);
        expectedConfigs.put("another.pid", dict);

        Map<String, Dictionary<String, Object>> actualConfigs = new HashMap<>();
        for (org.apache.sling.feature.Configuration conf : genFeat.getConfigurations()) {
            actualConfigs.put(conf.getPid(), conf.getProperties());
        }
        assertEquals(expectedConfigs, actualConfigs);
    }

    @Test
    public void testNonMatchingDirectoryIncludes() throws Exception {
        File featuresDir = new File(
                getClass().getResource("/aggregate-features/dir").getFile());
        // read features
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : featuresDir.listFiles((d,f) -> f.endsWith(".json"))) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        Aggregate fc = new Aggregate();
        fc.setFilesInclude("doesnotexist.json");

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        Artifact parentArtifact = createMockArtifact();
        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getGroupId()).thenReturn("org.foo");
        Mockito.when(mockProj.getArtifactId()).thenReturn("org.foo.bar");
        Mockito.when(mockProj.getVersion()).thenReturn("1.2.3-SNAPSHOT");
        Mockito.when(mockProj.getArtifact()).thenReturn(parentArtifact);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/rawmain.json-cache"))
            .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Preprocessor.class.getName())).thenReturn(Boolean.TRUE);

        AggregateFeaturesMojo af = new AggregateFeaturesMojo();
        fc.classifier = "aggregated";
        af.aggregates = Collections.singletonList(fc);
        af.project = mockProj;
        af.projectHelper = new DefaultMavenProjectHelper();
        af.features = featuresDir;

        try {
            af.execute();
            fail("Should have thrown an exception because doesnotexist.json is not a file");
        } catch (MojoExecutionException mee) {
            assertTrue(mee.getMessage().contains("Include doesnotexist.json not found"));
        }
    }

    @Test
    public void testNonMatchingDirectoryExcludes() throws Exception {
        File featuresDir = new File(
                getClass().getResource("/aggregate-features/dir").getFile());
        // read features
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : featuresDir.listFiles((d,f) -> f.endsWith(".json"))) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        Aggregate fc = new Aggregate();
        fc.setFilesInclude("doesnotexist.json");

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        Artifact parentArtifact = createMockArtifact();
        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getGroupId()).thenReturn("org.foo");
        Mockito.when(mockProj.getArtifactId()).thenReturn("org.foo.bar");
        Mockito.when(mockProj.getVersion()).thenReturn("1.2.3-SNAPSHOT");
        Mockito.when(mockProj.getArtifact()).thenReturn(parentArtifact);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/rawmain.json-cache"))
            .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Preprocessor.class.getName())).thenReturn(Boolean.TRUE);

        AggregateFeaturesMojo af = new AggregateFeaturesMojo();
        fc.classifier = "aggregated";
        af.aggregates = Collections.singletonList(fc);
        af.project = mockProj;
        af.projectHelper = new DefaultMavenProjectHelper();
        af.features = featuresDir;

        try {
            af.execute();
            fail("Should have thrown an exception because doesnotexist.json is not a file");
        } catch (MojoExecutionException mee) {
            assertTrue(mee.getMessage().contains("FeatureInclude doesnotexist.json not found"));
        }
    }

    @Test
    public void testIncludeOrdering() throws Exception {
        File featuresDir = new File(
                getClass().getResource("/aggregate-features/dir4").getFile());
        // read features
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : featuresDir.listFiles((d,f) -> f.endsWith(".json"))) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        Aggregate fc1 = new Aggregate();
        fc1.setFilesInclude("test_x.json");

        fc1.setFilesInclude("test_u.json");
        fc1.setFilesInclude("test_y.json");
        fc1.setFilesInclude("test_v.json");
        fc1.setFilesInclude("test_z.json");

        fc1.setFilesInclude("test_t.json");

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        Artifact parentArtifact = createMockArtifact();
        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getGroupId()).thenReturn("g");
        Mockito.when(mockProj.getArtifactId()).thenReturn("a");
        Mockito.when(mockProj.getVersion()).thenReturn("999");
        Mockito.when(mockProj.getArtifact()).thenReturn(parentArtifact);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/rawmain.json-cache"))
            .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Preprocessor.class.getName())).thenReturn(Boolean.TRUE);


        AggregateFeaturesMojo af = new AggregateFeaturesMojo();
        fc1.classifier = "agg";
        af.aggregates = Arrays.asList(fc1);
        af.project = mockProj;
        af.projectHelper = new DefaultMavenProjectHelper();
        af.features = featuresDir;

        af.execute();

        Feature genFeat = featureMap.get(":aggregate:agg");
        ArtifactId id = genFeat.getId();

        assertEquals("g", id.getGroupId());
        assertEquals("a", id.getArtifactId());
        assertEquals("999", id.getVersion());
        assertEquals(FeatureConstants.PACKAGING_FEATURE, id.getType());
        assertEquals("agg", id.getClassifier());

        Map<String, Dictionary<String, Object>> expectedConfigs = new HashMap<>();
        expectedConfigs.put("t.pid", new Hashtable<>(Collections.singletonMap("t", "t")));
        expectedConfigs.put("u.pid", new Hashtable<>(Collections.singletonMap("u", "u")));
        expectedConfigs.put("v.pid", new Hashtable<>(Collections.singletonMap("v", "v")));
        expectedConfigs.put("x.pid", new Hashtable<>(Collections.singletonMap("x", "x")));
        expectedConfigs.put("y.pid", new Hashtable<>(Collections.singletonMap("y", "y")));
        expectedConfigs.put("z.pid", new Hashtable<>(Collections.singletonMap("z", "z")));

        Map<String, Dictionary<String, Object>> actualConfigs = new HashMap<>();
        for (org.apache.sling.feature.Configuration conf : genFeat.getConfigurations()) {
            actualConfigs.put(conf.getPid(), conf.getProperties());
        }
        assertEquals(expectedConfigs, actualConfigs);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testReadFeatureFromArtifact() throws Exception {
       File featureFile = new File(
                getClass().getResource("/aggregate-features/test_x.json").getFile());
        // read feature
        Map<String, Feature> featureMap = new HashMap<>();
        Feature feat = FeatureJSONReader.read(new FileReader(featureFile), null);
        featureMap.put(featureFile.getAbsolutePath(), feat);

        Aggregate fc = new Aggregate();
        final Dependency dep = new Dependency();
        dep.setGroupId("g1");
        dep.setArtifactId("a1");
        dep.setVersion("9.9.9");
        dep.setType(FeatureConstants.PACKAGING_FEATURE);
        dep.setClassifier("c1");

        fc.setIncludeArtifact(dep);

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        Artifact parentArtifact = createMockArtifact();
        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getGroupId()).thenReturn("mygroup");
        Mockito.when(mockProj.getArtifactId()).thenReturn("myart");
        Mockito.when(mockProj.getVersion()).thenReturn("42");
        Mockito.when(mockProj.getArtifact()).thenReturn(parentArtifact);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/rawmain.json-cache"))
            .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Preprocessor.class.getName())).thenReturn(Boolean.TRUE);

        AggregateFeaturesMojo af = new AggregateFeaturesMojo();
        fc.classifier = "mynewfeature";
        af.aggregates = Collections.singletonList(fc);
        af.project = mockProj;
        af.mavenSession = Mockito.mock(MavenSession.class);
        af.projectHelper = new DefaultMavenProjectHelper();
        af.artifactHandlerManager = Mockito.mock(ArtifactHandlerManager.class);
        af.features = featureFile.getParentFile();

        af.artifactResolver = Mockito.mock(ArtifactResolver.class);
        Mockito.doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Artifact artifact = invocation.getArgumentAt(0, Artifact.class);
                if (artifact.getGroupId().equals(dep.getGroupId())
                        && artifact.getArtifactId().equals(dep.getArtifactId())
                        && artifact.getVersion().equals(dep.getVersion())
                        && artifact.getClassifier().equals(dep.getClassifier())
                        && artifact.getType().equals(dep.getType())) {
                    artifact.setFile(featureFile);
                    return null;
                }
                throw new ArtifactResolutionException("Not found", artifact);
            }
        }).when(af.artifactResolver).resolve(Mockito.any(Artifact.class), Mockito.anyList(), Mockito.any(ArtifactRepository.class));
        af.execute();

        Feature genFeat = featureMap.get(":aggregate:mynewfeature");
        ArtifactId id = genFeat.getId();
        assertEquals("mygroup", id.getGroupId());
        assertEquals("myart", id.getArtifactId());
        assertEquals("42", id.getVersion());
        assertEquals(FeatureConstants.PACKAGING_FEATURE, id.getType());
        assertEquals("mynewfeature", id.getClassifier());

        int numFound = 0;
        for (org.apache.sling.feature.Artifact art : genFeat.getBundles()) {
            numFound++;

            ArtifactId expectedBundleCoords =
                    new ArtifactId("mygroup", "org.apache.aries.util", "1.1.3", null, null);
            assertEquals(expectedBundleCoords, art.getId());
        }
        assertEquals("Expected only one bundle", 1, numFound);
    }

    @Test
    public void testPluginHandling() throws Exception {
        File featuresDir = new File(
                getClass().getResource("/aggregate-features/dir3").getFile());
        // read features
        Map<String, Feature> featureMap = new HashMap<>();
        for (File f : featuresDir.listFiles((d,f) -> f.endsWith(".json"))) {
            Feature feat = FeatureJSONReader.read(new FileReader(f), null);
            featureMap.put(f.getAbsolutePath(), feat);
        }

        Aggregate fc = new Aggregate();
        fc.setFilesInclude("*.json");

        Build mockBuild = Mockito.mock(Build.class);
        Mockito.when(mockBuild.getDirectory()).thenReturn(tempDir.toString());

        Artifact parentArtifact = createMockArtifact();
        MavenProject mockProj = Mockito.mock(MavenProject.class);
        Mockito.when(mockProj.getBuild()).thenReturn(mockBuild);
        Mockito.when(mockProj.getGroupId()).thenReturn("org.foo");
        Mockito.when(mockProj.getArtifactId()).thenReturn("org.foo.bar");
        Mockito.when(mockProj.getVersion()).thenReturn("1.2.3-SNAPSHOT");
        Mockito.when(mockProj.getArtifact()).thenReturn(parentArtifact);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/rawmain.json-cache"))
            .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Feature.class.getName() + "/assembledmain.json-cache"))
            .thenReturn(featureMap);
        Mockito.when(mockProj.getContextValue(Preprocessor.class.getName())).thenReturn(Boolean.TRUE);

        AggregateFeaturesMojo af = new AggregateFeaturesMojo();
        fc.classifier = "aggregated";
        af.aggregates = Collections.singletonList(fc);
        af.project = mockProj;
        af.projectHelper = new DefaultMavenProjectHelper();
        af.features = featuresDir;
        af.handlerConfiguration = new HashMap<>();

        Properties p3props = new Properties();
        p3props.put("test3cfg", "myval");
        p3props.put("test3cfg3", "somethingelse");
        af.handlerConfiguration.put("TestPlugin3", p3props);

        assertEquals("Precondition", 0, pluginCallbacks.size());
        af.execute();

        ArtifactId id = new ArtifactId("org.foo", "org.foo.bar", "1.2.3-SNAPSHOT", "aggregated", FeatureConstants.PACKAGING_FEATURE);
        assertEquals(id, pluginCallbacks.get("TestPlugin1 - extension1"));
        assertEquals(id, pluginCallbacks.get("TestPlugin1 - extension2"));
        assertEquals(id, pluginCallbacks.get("TestPlugin1 - extension3"));
        assertEquals(id, pluginCallbacks.get("TestPlugin2 - extension1"));
        assertEquals(id, pluginCallbacks.get("TestPlugin2 - extension2"));
        assertEquals(id, pluginCallbacks.get("TestPlugin2 - extension3"));

        ArtifactId id2 = new ArtifactId("test", "test", "9.9.9", "y", "slingosgifeature");
        assertEquals(id2, pluginCallbacks.get("TestPlugin3 - myval-Hi there"));
    }

    private Artifact createMockArtifact() {
        Artifact parentArtifact = Mockito.mock(Artifact.class);
        Mockito.when(parentArtifact.getGroupId()).thenReturn("gid");
        Mockito.when(parentArtifact.getArtifactId()).thenReturn("aid");
        Mockito.when(parentArtifact.getVersionRange()).thenReturn(VersionRange.createFromVersion("123"));
        Mockito.when(parentArtifact.getType()).thenReturn("foo");
        return parentArtifact;
    }
}
