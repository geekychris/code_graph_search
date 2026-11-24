package com.codegraph.app.knowledge;

import com.codegraph.core.knowledge.Architecture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureBuilderTest {

    private static FileGraph.FileNode f(String path, int elements) {
        return new FileGraph.FileNode(path, "java", elements, 1);
    }

    /** A Maven layout where one package root holds almost everything, plus a vendored package and tests. */
    private static final FileGraph G = new FileGraph("r", "c1", List.of(
            f("src/main/java/com/h/util/sched/Scheduler.java", 400),
            f("src/main/java/com/h/util/sched/Job.java", 100),
            f("src/main/java/com/h/util/cache/Cache.java", 300),
            f("src/main/java/com/h/util/Util.java", 50),
            f("src/main/java/im/rore/zk/Zk.java", 10),
            f("src/test/java/com/h/util/sched/SchedulerTest.java", 40)),
            List.of(new FileGraph.FileEdge(0, 2, null, null, Map.of("CALLS", 5)),
                    new FileGraph.FileEdge(5, 0, null, null, Map.of("CALLS", 3)),
                    new FileGraph.FileEdge(2, -1, "other", "com.o.Thing", Map.of("CALLS", 2))),
            List.of(new FileGraph.TypeNode("t1", "Scheduler", "com.h.util.sched.Scheduler", "CLASS", 0, 3, 1)));

    @Test
    void opensTheDominantPackageRootInPlace() {
        Architecture a = ArchitectureBuilder.fold(G, "", r -> true);
        assertThat(a.groups()).extracting(Architecture.Group::id).containsExactlyInAnyOrder(
                "com/h/util/sched", "com/h/util/cache", "com/h/util/Util.java", "im/rore/zk", "test/com/h/util/sched");
        assertThat(a.groups()).extracting(Architecture.Group::label).containsExactlyInAnyOrder(
                "util/sched", "cache", "Util.java", "im/…/zk", "test/…/sched");
        assertThat(a.edges()).extracting(e -> e.from() + ">" + e.to())
                .containsExactlyInAnyOrder("com/h/util/sched>com/h/util/cache", "test/com/h/util/sched>com/h/util/sched");
        assertThat(a.external()).singleElement().satisfies(x -> {
            assertThat(x.repoId()).isEqualTo("other");
            assertThat(x.fromGroups()).containsEntry("com/h/util/cache", 2);
        });
        assertThat(a.external()).isNotEmpty();
        assertThat(ArchitectureBuilder.fold(G, "", r -> false).external()).isEmpty();
    }

    @Test
    void drillsIntoAGroup() {
        Architecture a = ArchitectureBuilder.fold(G, "com/h/util/sched", r -> true);
        assertThat(a.groups()).extracting(Architecture.Group::label)
                .containsExactlyInAnyOrder("Scheduler.java", "Job.java", "rest of repo");
        assertThat(a.keyTypes()).extracting(Architecture.KeyType::name).containsExactly("Scheduler");
    }
}
