/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */
package org.geogit.api.plumbing;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

import javax.annotation.Nullable;

import org.geogit.api.Bucket;
import org.geogit.api.CommitBuilder;
import org.geogit.api.GeoGIT;
import org.geogit.api.Node;
import org.geogit.api.NodeRef;
import org.geogit.api.ObjectId;
import org.geogit.api.Ref;
import org.geogit.api.RevCommit;
import org.geogit.api.RevFeature;
import org.geogit.api.RevFeatureBuilder;
import org.geogit.api.RevFeatureType;
import org.geogit.api.RevObject.TYPE;
import org.geogit.api.RevTree;
import org.geogit.api.RevTreeBuilder;
import org.geogit.api.RevTreeImpl;
import org.geogit.api.SymRef;
import org.geogit.api.plumbing.LsTreeOp.Strategy;
import org.geogit.api.plumbing.diff.MutableTree;
import org.geogit.repository.SpatialOps;
import org.geogit.storage.ObjectDatabase;
import org.geogit.storage.StagingDatabase;
import org.geogit.test.integration.RepositoryTestCase;
import org.junit.Test;
import org.opengis.feature.Feature;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.io.ParseException;

public class WriteTree2Test extends RepositoryTestCase {

    private static final String EMPTY_ID = RevTree.EMPTY_TREE_ID.toString();

    private WriteTree2 command;

    private GeoGIT geogit;

    private StagingDatabase indexDb;

    private ObjectDatabase objectDb;

    private RevTree leftTree;

    private RevTree rightTree;

    @Override
    protected void setUpInternal() throws Exception {
        geogit = getGeogit();
        command = geogit.command(WriteTree2.class);
        indexDb = geogit.getRepository().stagingDatabase();
        objectDb = geogit.getRepository().objectDatabase();
    }

    @Override
    public void tearDownInternal() {
        if (objectDb != null) {
            objectDb.close();
        }
        if (indexDb != null) {
            indexDb.close();
        }
    }

    @Test
    public void testEmptyRepo() {
        ObjectId root = command.call();
        assertNotNull(root);
        assertEquals(RevTree.EMPTY_TREE_ID, root);
    }

    @Test
    public void testEmptyRepoSingleStagedTree() {
        rightTree = createStageHeadTree(//
        indexTree("roads", "a1", "d1", 10)//
        );

        ObjectId newRepoRoot = command.call();
        assertNotNull(newRepoRoot);
        // print(newRepoRoot);

        // check all blobs have been moved from the index to the object database
        verifyRepositoryTree(NodeRef.ROOT, newRepoRoot);
        ImmutableMap<String, NodeRef> refsByPath = getTreeRefsByPath(newRepoRoot);
        assertEquals(1, refsByPath.size());
        assertTrue(refsByPath.keySet().contains("roads"));
    }

    @Test
    public void testRename() {
        leftTree = createHeadTree(//
        repoTree("roads", EMPTY_ID, null, 0) //
        );
        rightTree = createStageHeadTree(//
        indexTree("roadsRenamed", EMPTY_ID, null, 0) //
        );

        ObjectId newRepoRoot = command.call();
        assertNotNull(newRepoRoot);
        // check all blobs have been moved from the index to the object database
        verifyRepositoryTree(NodeRef.ROOT, newRepoRoot);

        ImmutableMap<String, NodeRef> refsByPath = getTreeRefsByPath(newRepoRoot);
        assertEquals(1, refsByPath.size());
        assertTrue(refsByPath.containsKey("roadsRenamed"));
    }

    @Test
    public void testRenameNested() {
        leftTree = createHeadTree(//
                repoTree("roads", EMPTY_ID, null, 0), //
                repoTree("roads/highways", "a2", "d1", 2),//
                repoTree("roads/streets", "a3", "d2", 2) //
        );
        rightTree = createStageHeadTree(//
                indexTree("roads", EMPTY_ID, null, 0), //
                indexTree("roads/highways", "a2", "d1", 2),//
                indexTree("roads/streetsRenamed", "a3", "d2", 2) //
        );

        ObjectId newRepoRoot = command.call();
        assertNotNull(newRepoRoot);
        // check all blobs have been moved from the index to the object database
        verifyRepositoryTree(NodeRef.ROOT, newRepoRoot);

        ImmutableMap<String, NodeRef> refsByPath = getTreeRefsByPath(newRepoRoot);
        assertEquals(3, refsByPath.size());
        assertTrue(refsByPath.containsKey("roads"));
        assertTrue(refsByPath.containsKey("roads/highways"));
        assertTrue(refsByPath.containsKey("roads/streetsRenamed"));
    }

    @Test
    public void testNoChanges() {
        leftTree = createHeadTree(//
                repoTree("buildings", EMPTY_ID, null, 0),//
                repoTree("buildings/stores", "a5", "d3", 5),//
                repoTree("buildings/unknown", "a6", "d4", 5),//
                repoTree("buildings/towers", "a7", "d5", 5)//
        );
        rightTree = createStageHeadTree(//
                repoTree("buildings", EMPTY_ID, null, 0),//
                repoTree("buildings/stores", "a5", "d3", 5),//
                repoTree("buildings/unknown", "a6", "d4", 5),//
                repoTree("buildings/towers", "a7", "d5", 5)//
        );

        ObjectId newRepoRoot = command.call();
        assertNotNull(newRepoRoot);
        // check all blobs have been moved from the index to the object database
        verifyRepositoryTree(NodeRef.ROOT, newRepoRoot);

        ImmutableMap<String, NodeRef> refsByPath = getTreeRefsByPath(newRepoRoot);
        assertEquals(4, refsByPath.size());
        Set<String> expected = set("buildings", "buildings/stores", "buildings/unknown",
                "buildings/towers");
        assertEquals(expected, refsByPath.keySet());
    }

    @Test
    public void testMetadataIdChangeOnly() {
        leftTree = createHeadTree(//
                repoTree("buildings", EMPTY_ID, null, 0),//
                repoTree("buildings/stores", "a5", "d3", 5)// old md id is d3
        );
        rightTree = createStageHeadTree(//
                indexTree("buildings", EMPTY_ID, null, 0),//
                indexTree("buildings/stores", "a5", "d31", 5)// new md id is d31
        );

        ObjectId newRepoRoot = command.call();
        assertNotNull(newRepoRoot);
        // check all blobs have been moved from the index to the object database
        verifyRepositoryTree(NodeRef.ROOT, newRepoRoot);
        ImmutableMap<String, NodeRef> refsByPath = getTreeRefsByPath(newRepoRoot);
        assertEquals(set("buildings", "buildings/stores"), refsByPath.keySet());
        assertEquals(id("d31"), refsByPath.get("buildings/stores").getMetadataId());
    }

    @Test
    public void testDeleteAll() {
        leftTree = createHeadTree(//
                repoTree("roads", EMPTY_ID, null, 0), //
                repoTree("roads/highways", "a2", "d1", 10),//
                repoTree("roads/streets", "a3", "d2", 10), //
                repoTree("buildings", EMPTY_ID, null, 0),//
                repoTree("buildings/stores", "a5", "d3", 5),//
                repoTree("buildings/unknown", "a6", "d4", 5),//
                repoTree("buildings/towers", "a7", "d5", 5)//
        );
        rightTree = createStageHeadTree();

        final ObjectId newRepoRoot = command.call();
        assertNotNull(newRepoRoot);

        ImmutableMap<String, NodeRef> refsByPath = getTreeRefsByPath(newRepoRoot);
        assertEquals(set(), refsByPath.keySet());
    }

    @Test
    public void testDeletes() {
        leftTree = createHeadTree(//
                repoTree("roads", EMPTY_ID, null, 0), //
                repoTree("roads/highways", "a2", "d1", 10),//
                repoTree("roads/streets", "a3", "d2", 10), //
                repoTree("buildings", EMPTY_ID, null, 0),//
                repoTree("buildings/stores", "a5", "d3", 5),//
                repoTree("buildings/unknown", "a6", "d4", 5),//
                repoTree("buildings/towers", "a7", "d5", 5)//
        );
        rightTree = createStageHeadTree(//
                indexTree("roads", EMPTY_ID, null, 0), //
                indexTree("roads/highways", "a2", "d1", 10),//
                indexTree("buildings", EMPTY_ID, null, 0),//
                indexTree("buildings/stores", "a5", "d31", 5)//
        );

        final ObjectId newRepoRoot = command.call();
        assertNotNull(newRepoRoot);

        // check all blobs have been moved from the index to the object database
        verifyRepositoryTree(NodeRef.ROOT, newRepoRoot);

        ImmutableMap<String, NodeRef> refsByPath = getTreeRefsByPath(newRepoRoot);
        assertEquals(set("roads", "roads/highways", "buildings", "buildings/stores"),
                refsByPath.keySet());
    }

    @Test
    public void testSimpleChanges() {
        leftTree = createHeadTree(//
                repoTree("roads", "a1", "d1", 1), //
                repoTree("buildings", EMPTY_ID, null, 0)//
        );
        rightTree = createStageHeadTree(//
                repoTree("roads", "a11", "d1", 2), //
                repoTree("buildings", "a41", null, 1)//
        );
        // print(leftTree.getId());
        // print(rightTree.getId());
        final ObjectId newRepoRoot = command.call();
        assertNotNull(newRepoRoot);
        // check all blobs have been moved from the index to the object database
        verifyRepositoryTree(NodeRef.ROOT, newRepoRoot);

        ImmutableMap<String, NodeRef> refsByPath = getRefsByPath(newRepoRoot, true);

        Set<String> expected = set("roads", "roads/roads.0", "roads/roads.1", "buildings",
                "buildings/buildings.0");
        ImmutableSet<String> actual = refsByPath.keySet();

        assertEquals(expected, actual);
    }

    @Test
    public void testNestedChanges() {
        leftTree = createHeadTree(//
                repoTree("roads", EMPTY_ID, null, 0), //
                repoTree("roads/highways", EMPTY_ID, "d1", 0),//
                repoTree("roads/streets", "a3", "d2", 1), //
                repoTree("buildings", EMPTY_ID, null, 0),//
                repoTree("buildings/stores", EMPTY_ID, "d3", 0),//
                repoTree("buildings/unknown", "a6", "d4", 1)//
        );
        rightTree = createStageHeadTree(//
                indexTree("roads", EMPTY_ID, null, 0), //
                indexTree("roads/highways", "a21", "d1", 1),// 1 added
                indexTree("roads/streets", EMPTY_ID, "d2", 0), // 1 removed
                indexTree("buildings", EMPTY_ID, null, 0),//
                indexTree("buildings/stores", "a51", "d3", 2),// 2 added
                indexTree("buildings/unknown", "a6", "d4", 1)// not changed
        );

        final ObjectId newRepoRoot = command.call();
        assertNotNull(newRepoRoot);
        // check all blobs have been moved from the index to the object database
        verifyRepositoryTree(NodeRef.ROOT, newRepoRoot);

        ImmutableMap<String, NodeRef> refsByPath = getRefsByPath(newRepoRoot, true);

        Set<String> expected = set("roads", "roads/highways", "roads/highways/highways.0",
                "roads/streets", "buildings", "buildings/stores", "buildings/stores/stores.0",
                "buildings/stores/stores.1", "buildings/unknown", "buildings/unknown/unknown.0");
        ImmutableSet<String> actual = refsByPath.keySet();

        assertEquals(expected, actual);
    }

    @Test
    public void testAllKindsOfChanges() {
        leftTree = createHeadTree(//
                repoTree("roads", EMPTY_ID, null, 0), //
                repoTree("roads/highways", "a2", "d1", 1),//
                repoTree("roads/streets", "a3", "d2", 1), //
                repoTree("buildings", "a4", null, 2),// mixed tree, contains features and subtrees
                repoTree("buildings/stores", "a5", "d3", 1),//
                repoTree("buildings/unknown", "a6", "d4", 1),//
                repoTree("buildings/towers", "a7", "d5", 5)//
        );
        rightTree = createStageHeadTree(//
                indexTree("roads", EMPTY_ID, null, 0), //
                indexTree("roads/highways", "a21", "d1", 2),// 1 feature added
                indexTree("roads/streetsRenamed", "a3", "d2", 1), // tree renamed
                indexTree("buildings", "a41", null, 1),// 1 feature removed
                indexTree("buildings/stores", "a5", "d31", 1),// only metadata changed
                indexTree("buildings/knownUnknown", "a61", "d41", 2),// renamed, changed tree and
                                                                     // metadata
                // buildings/towers removed completely
                indexTree("admin", "c1", "d5", 2),// new mixed tree, contains features and subtrees
                indexTree("admin/area", "c2", "d6", 1),//
                indexTree("admin/line", EMPTY_ID, "d7", 0)//
        );

        final ObjectId newRepoRoot = command.call();
        // print(newRepoRoot);
        assertNotNull(newRepoRoot);
        // check all blobs have been moved from the index to the object database
        verifyRepositoryTree(NodeRef.ROOT, newRepoRoot);

        ImmutableMap<String, NodeRef> refsByPath = getRefsByPath(newRepoRoot, true);
        Set<String> paths = Sets.newTreeSet();
        paths.addAll(refsByPath.keySet());

        Set<String> expected = set(//
                "roads",//
                "roads/highways",//
                "roads/highways/highways.0",//
                "roads/highways/highways.1",//
                "roads/streetsRenamed",//
                "roads/streetsRenamed/streets.0",//
                "buildings",//
                "buildings/buildings.0",//
                "buildings/stores",//
                "buildings/stores/stores.0",//
                "buildings/knownUnknown",//
                "buildings/knownUnknown/knownUnknown.0",//
                "buildings/knownUnknown/knownUnknown.1",//
                "admin",//
                "admin/admin.0",//
                "admin/admin.1",//
                "admin/area",//
                "admin/area/area.0",//
                "admin/line"//
        );
        assertEquals(expected, paths);
    }

    @Test
    public void testPathFilteringTopLevelTree() {
        leftTree = createHeadTree(//
                repoTree("roads", "a1", null, 2), //
                repoTree("roads/highways", "a2", "d1", 1),//
                repoTree("roads/streets", "a3", "d2", 2),//
                repoTree("buildings", "a4", "d3", 2)// deleted tree completely
        );
        rightTree = createStageHeadTree(//
                repoTree("roads", "a11", null, 1), // deleted 1 feature
                repoTree("roads/highways", "a21", "d1", 3),// added 2 features
                repoTree("roads/streets", "a31", "d2", 1) // removed 1 feature
        );

        MapDifference<String, NodeRef> difference;
        Set<String> onlyOnLeft;
        Set<String> onlyOnRight;
        Set<String> entriesInCommon;

        difference = runWithPathFilter(leftTree, rightTree, "roads");
        onlyOnLeft = difference.entriesOnlyOnLeft().keySet();
        onlyOnRight = difference.entriesOnlyOnRight().keySet();
        entriesInCommon = difference.entriesInCommon().keySet();
        Set<String> entriesDiffering = difference.entriesDiffering().keySet();

        assertEquals(set("buildings", "buildings/buildings.0", "buildings/buildings.1"), onlyOnLeft);
        assertEquals(set(), onlyOnRight);
        assertEquals(
                set("roads", "roads/roads.0", "roads/streets/streets.0",
                        "roads/highways/highways.0", "roads/highways/highways.2",
                        "roads/highways/highways.1", "roads/highways", "roads/streets"),
                entriesInCommon);
        assertEquals(set(), entriesDiffering);

    }

    @Test
    public void testPathFilteringSingleFeature() {
        leftTree = createHeadTree(//
                repoTree("roads", "a1", null, 2), //
                repoTree("roads/highways", "a2", "d1", 1)//
        );
        rightTree = createStageHeadTree(//
                repoTree("roads", "a11", null, 1), // deleted 1 feature
                repoTree("roads/highways", "a21", "d1", 3)// added 2 features
        );

        MapDifference<String, NodeRef> difference;
        Set<String> onlyOnLeft;
        Set<String> onlyOnRight;

        difference = runWithPathFilter(leftTree, rightTree, "roads/roads.1");
        onlyOnLeft = difference.entriesOnlyOnLeft().keySet();
        onlyOnRight = difference.entriesOnlyOnRight().keySet();

        assertEquals(set(), onlyOnLeft);
        assertEquals(set("roads/highways/highways.1", "roads/highways/highways.2"), onlyOnRight);
        assertEquals(set("roads/highways/highways.0", "roads/roads.0"), difference
                .entriesInCommon().keySet());
    }

    @Test
    public void testPathFilteringDeletedTreeButCommitSingleChange() {
        leftTree = createHeadTree(//
                repoTree("roads", "a1", null, 1), //
                repoTree("highways", "a2", "d1", 2)//
        );
        rightTree = createStageHeadTree(//
        repoTree("roads", "a1", null, 1)
        // deleted highways
        );

        MapDifference<String, NodeRef> difference;
        Set<String> onlyOnNewTree;
        Set<String> onlyStaged;

        difference = runWithPathFilter(leftTree, rightTree, "highways/highways.1");
        onlyOnNewTree = difference.entriesOnlyOnLeft().keySet();
        onlyStaged = difference.entriesOnlyOnRight().keySet();
        Set<String> differing = difference.entriesDiffering().keySet();
        Set<String> inCommon = difference.entriesInCommon().keySet();

        assertEquals(set("roads", "roads/roads.0"), inCommon);
        assertEquals(set("highways", "highways/highways.0"), onlyOnNewTree);
        assertEquals(set(), differing);
        assertEquals(set(), onlyStaged);
    }

    @Test
    public void testFilteredAddsFirstCommit() {
        leftTree = createHeadTree();
        rightTree = createStageHeadTree(//
                repoTree("points", "a1", null, 3), //
                repoTree("lines", "b1", null, 2) //
        );

        MapDifference<String, NodeRef> difference;
        Set<String> onlyOnLeft;
        Set<String> onlyOnRight;
        Set<String> entriesDiffering;
        Set<String> entriesInCommon;

        difference = runWithPathFilter(leftTree, rightTree, "points/points.1", "points/points.2");
        onlyOnLeft = difference.entriesOnlyOnLeft().keySet();
        onlyOnRight = difference.entriesOnlyOnRight().keySet();
        entriesDiffering = difference.entriesDiffering().keySet();
        entriesInCommon = difference.entriesInCommon().keySet();

        assertEquals(set(), onlyOnLeft);
        assertEquals(set("points"), entriesDiffering);
        assertEquals(set("points/points.0", "lines", "lines/lines.0", "lines/lines.1"), onlyOnRight);
        assertEquals(set("points/points.1", "points/points.2"), entriesInCommon);

        difference = runWithPathFilter(leftTree, rightTree, "lines/lines.1", "badFilter");
        onlyOnLeft = difference.entriesOnlyOnLeft().keySet();
        onlyOnRight = difference.entriesOnlyOnRight().keySet();
        entriesDiffering = difference.entriesDiffering().keySet();
        entriesInCommon = difference.entriesInCommon().keySet();

        assertEquals(set(), onlyOnLeft);
        assertEquals(
                set("lines/lines.0", "points", "points/points.0", "points/points.1",
                        "points/points.2"), onlyOnRight);
        assertEquals(set("lines"), entriesDiffering);
        assertEquals(set("lines/lines.1"), entriesInCommon);
    }

    /**
     * @return the differences between the given right tree(staged) and the resulting tree after
     *         running {@link WriteTree2} with the given filters. The result's left refs are the
     *         ones in the new tree, and the right refs the same as given in the rightTree
     */
    private MapDifference<String, NodeRef> runWithPathFilter(RevTree leftTree, RevTree rightTree,
            String... filters) {

        // print(leftTree.getId());
        // print(rightTree.getId());

        final ObjectId newRepoRoot = command.setPathFilter(Arrays.asList(filters)).call();
        assertNotNull(newRepoRoot);
        // print(newRepoRoot);

        // check all blobs have been moved from the index to the object database
        verifyRepositoryTree(NodeRef.ROOT, newRepoRoot);

        final boolean includeFeatures = true;
        ImmutableMap<String, NodeRef> stagedRefs = getRefsByPath(rightTree.getId(), includeFeatures);

        ImmutableMap<String, NodeRef> resultRefs = getRefsByPath(newRepoRoot, includeFeatures);

        MapDifference<String, NodeRef> difference = Maps.difference(resultRefs, stagedRefs);

        return difference;
    }

    private ImmutableMap<String, NodeRef> getTreeRefsByPath(ObjectId newRepoRoot) {
        Iterator<NodeRef> iterator = geogit.command(LsTreeOp.class)
                .setReference(newRepoRoot.toString()).setStrategy(Strategy.DEPTHFIRST_ONLY_TREES)
                .call();
        Function<NodeRef, String> keyFunction = new Function<NodeRef, String>() {
            @Override
            public String apply(NodeRef input) {
                return input.path();
            }
        };
        ImmutableMap<String, NodeRef> refsByPath = Maps.uniqueIndex(iterator, keyFunction);
        return refsByPath;
    }

    private ImmutableMap<String, NodeRef> getRefsByPath(ObjectId repoRoot, boolean includeFeatures) {

        Strategy strategy = includeFeatures ? Strategy.DEPTHFIRST : Strategy.DEPTHFIRST_ONLY_TREES;
        Iterator<NodeRef> iterator = geogit.command(LsTreeOp.class)
                .setReference(repoRoot.toString()).setStrategy(strategy).call();
        Function<NodeRef, String> keyFunction = new Function<NodeRef, String>() {
            @Override
            public String apply(NodeRef input) {
                return input.path();
            }
        };
        ImmutableMap<String, NodeRef> refsByPath = Maps.uniqueIndex(iterator, keyFunction);
        return refsByPath;
    }

    private void print(ObjectId treeId) {
        System.err.println(treeId);
        Iterator<NodeRef> iterator = geogit.command(LsTreeOp.class).setReference(treeId.toString())
                .setStrategy(Strategy.DEPTHFIRST).call();
        while (iterator.hasNext()) {
            print(iterator.next());
        }
    }

    private void print(NodeRef ref) {
        System.err.printf("\t%s '%s' -> %s (%s)\n", ref.getType().toString().charAt(0), ref.path(),
                ref.objectId(), ref.getNode().getMetadataId());
    }

    private void verifyRepositoryTree(String path, ObjectId repoTreeId) {
        ObjectDatabase objectDb = this.objectDb;

        verifyTree(objectDb, path, repoTreeId);
    }

    private void verifyTree(ObjectDatabase objectDb, String path, ObjectId repoTreeId) {
        assertTrue(String.format("tree '%s' (%s) is not present", path, repoTreeId),
                objectDb.exists(repoTreeId));

        RevTree tree = objectDb.getTree(repoTreeId);

        Iterator<Node> children = tree.children();
        while (children.hasNext()) {
            final Node node = children.next();
            if (TYPE.TREE.equals(node.getType())) {
                path = NodeRef.appendChild(path, node.getName());
                ObjectId objectId = node.getObjectId();
                verifyRepositoryTree(path, objectId);
            } else if (TYPE.FEATURE.equals(node.getType())) {
                verifyFeature(node);
            } else {
                throw new IllegalStateException(node.getType().toString());
            }
            verifyMetadata(node);
        }
        if (tree.buckets().isPresent()) {
            ImmutableCollection<Bucket> buckets = tree.buckets().get().values();
            for (Bucket b : buckets) {
                ObjectId bucketTreeId = b.id();
                verifyRepositoryTree(path + "/" + bucketTreeId.toString().substring(0, 8),
                        bucketTreeId);
            }
        }
    }

    private void verifyFeature(Node node) {
        ObjectId objectId = node.getObjectId();
        assertTrue("feature " + node.getName() + " -> " + objectId + " is not present in objectDb",
                objectDb.exists(objectId));
    }

    private void verifyMetadata(Node node) {
        if (node.getMetadataId().isPresent()) {
            ObjectId mdId = node.getMetadataId().get();
            String msg = "RevFeatureType " + mdId + " is not present (from " + node.getName() + ")";
            assertTrue(msg, objectDb.exists(mdId));
        }
    }

    private RevTree createHeadTree(NodeRef... treeRefs) {
        RevTree root = createFromRefs(objectDb, treeRefs);
        objectDb.put(root);
        CommitBuilder cb = new CommitBuilder(geogit.getPlatform());
        ObjectId treeId = root.getId();

        RevCommit commit = cb.setTreeId(treeId).setCommitter("Gabriel Roldan")
                .setAuthor("Gabriel Roldan").build();
        objectDb.put(commit);

        SymRef head = (SymRef) geogit.command(RefParse.class).setName(Ref.HEAD).call().get();
        final String currentBranch = head.getTarget();

        geogit.command(UpdateRef.class).setName(currentBranch).setNewValue(commit.getId()).call();

        verifyRepositoryTree(NodeRef.ROOT, treeId);
        verifyTreeStructure(treeId, treeRefs);

        return root;
    }

    private void verifyTreeStructure(ObjectId treeId, NodeRef... treeRefs) {
        Function<NodeRef, String> function = new Function<NodeRef, String>() {
            @Override
            public String apply(NodeRef input) {
                return input.path();
            }
        };
        Set<String> expectedPaths = ImmutableSet.copyOf(Iterables.transform(
                Arrays.asList(treeRefs), function));

        ImmutableMap<String, NodeRef> refs = getTreeRefsByPath(treeId);

        assertEquals(expectedPaths, refs.keySet());
    }

    private RevTree createStageHeadTree(NodeRef... treeRefs) {
        RevTree root = createFromRefs(indexDb, treeRefs);
        geogit.command(UpdateRef.class).setName(Ref.STAGE_HEAD).setNewValue(root.getId()).call();
        return root;
    }

    private RevTree createFromRefs(ObjectDatabase targetDb, NodeRef... treeRefs) {
        MutableTree mutableTree = MutableTree.createFromRefs(RevTree.EMPTY_TREE_ID, treeRefs);
        RevTree tree = mutableTree.build(indexDb, targetDb);
        return tree;
    }

    private NodeRef indexTree(String path, String id, String mdId, int numFeatures) {
        return tree(indexDb, path, id, mdId, numFeatures);
    }

    private NodeRef repoTree(String path, String id, String mdId, int numFeatures) {
        return tree(objectDb, path, id, mdId, numFeatures);
    }

    /**
     * Creates a tree reference for testing, forcing the specified id and metadata id, and with the
     * specified number of features (zero or more).
     * <p>
     * Note the tree is saved to the specified database only if its a leaf tree (more than zero
     * features), in order for the {@link #createFromRefs} method to be able of saving the parent
     */
    private NodeRef tree(ObjectDatabase db, String path, String id, String mdId, int numFeatures) {
        Preconditions.checkArgument(numFeatures != 0 || EMPTY_ID.equals(id),
                "for zero features trees use RevTree.EMPTY_TREE_ID");
        final ObjectId treeId = id(id);
        final ObjectId metadataId = id(mdId);
        final String feturePrefix = NodeRef.nodeFromPath(path);
        RevTreeBuilder b = new RevTreeBuilder(db);
        if (numFeatures > 0) {
            for (int i = 0; i < numFeatures; i++) {
                Node fn = feature(db, feturePrefix, i);
                b.put(fn);
            }
        }
        RevTree fakenId = forceTreeId(b, treeId);
        if (!db.exists(fakenId.getId())) {
            db.put(fakenId);
        }
        if (!metadataId.isNull()) {
            RevFeatureType fakeType = new RevFeatureType(metadataId, pointsType);
            if (!db.exists(fakeType.getId())) {
                db.put(fakeType);
            }
        }

        String name = NodeRef.nodeFromPath(path);
        String parent = NodeRef.parentPath(path);

        Envelope bounds = SpatialOps.boundsOf(fakenId);
        Node node = Node.create(name, treeId, metadataId, TYPE.TREE, bounds);
        return new NodeRef(node, parent, ObjectId.NULL);
    }

    private RevTree forceTreeId(RevTreeBuilder b, ObjectId treeId) {
        RevTree tree = b.build();
        RevTree fakenId = RevTreeImpl.create(treeId, tree.size(), tree);
        return fakenId;
    }

    private String point(int i) {
        return "POINT(" + i + " " + i + ")";
    }

    private Node feature(ObjectDatabase db, String idPrefix, int index) {
        final String id = idPrefix + "." + index;
        final Feature feature;
        try {
            feature = super.feature(pointsType, id, id, index, point(index));
        } catch (ParseException e) {
            throw Throwables.propagate(e);
        }
        RevFeature revFeature = new RevFeatureBuilder().build(feature);
        db.put(revFeature);
        Envelope bounds = (Envelope) feature.getBounds();
        return Node.create(id, revFeature.getId(), ObjectId.NULL, TYPE.FEATURE, bounds);
    }

    private static ObjectId id(@Nullable String partialHash) {
        if (partialHash == null) {
            return ObjectId.NULL;
        }
        partialHash = Strings.padEnd(partialHash, 2 * ObjectId.NUM_BYTES, '0');
        return ObjectId.valueOf(partialHash);
    }

    private ImmutableSet<String> set(String... contents) {
        if (contents == null) {
            return ImmutableSet.of();
        }
        return ImmutableSet.copyOf(contents);
    }
}
