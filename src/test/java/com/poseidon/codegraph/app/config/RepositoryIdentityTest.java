package com.poseidon.codegraph.app.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RepositoryIdentityTest {
    @Test void transportAliasesShareIdentityButGroupsAndHostsDoNot() {
        assertThat(RepositoryIdentity.canonical("git@github.com:Org/Repo.git"))
            .isEqualTo(RepositoryIdentity.canonical("https://github.com/org/repo/"))
            .isEqualTo(RepositoryIdentity.canonical("ssh://git@github.com:22/org/repo.git"));
        assertThat(RepositoryIdentity.canonical("https://gitlab.com/team/sub/Repo.git"))
            .isEqualTo("gitlab.com/team/sub/Repo");
        assertThat(RepositoryIdentity.canonical("https://github.com/a/demo.git"))
            .isNotEqualTo(RepositoryIdentity.canonical("https://github.com/b/demo.git"))
            .isNotEqualTo(RepositoryIdentity.canonical("https://gitlab.com/a/demo.git"));
        assertThatThrownBy(() -> RepositoryIdentity.canonical("https://token@github.com/a/demo.git"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void scopeIsStableAcrossTasksAndBranchesAndDifferentAcrossProjects() {
        var a = new RepositoryIdentity("uuid-a", "key", "github.com/a/demo");
        var b = new RepositoryIdentity("uuid-b", "key2", "github.com/b/demo");
        assertThat(a.graphScope()).isEqualTo(a.graphScope())
            .isNotEqualTo(b.graphScope());
        assertThat(RepositoryIdentity.hash("github.com/a/demo")).hasSize(64);
    }
    @Test void projectIdIsDeterministicAndFixedLength() {
        String a = RepositoryIdentity.projectId("github.com/team/demo");
        assertThat(a).hasSize(26).matches("[A-Z2-7]{26}")
            .isEqualTo(RepositoryIdentity.projectId("github.com/team/demo"))
            .isNotEqualTo(RepositoryIdentity.projectId("github.com/other/demo"));
    }
}
