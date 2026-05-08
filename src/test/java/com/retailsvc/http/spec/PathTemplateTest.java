package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PathTemplateTest {
  @Test
  void exactPathMatchesItself() {
    PathTemplate t = PathTemplate.compile("/users");
    assertThat(t.match("/users")).isPresent();
    assertThat(t.match("/users").get()).isEmpty();
  }

  @Test
  void exactPathDoesNotMatchOther() {
    assertThat(PathTemplate.compile("/users").match("/orders")).isEmpty();
  }

  @Test
  void singleParamExtracted() {
    PathTemplate t = PathTemplate.compile("/users/{id}");
    assertThat(t.match("/users/42"))
        .hasValueSatisfying(m -> assertThat(m).containsEntry("id", "42"));
    assertThat(t.parameterNames()).containsExactly("id");
  }

  @Test
  void twoParamsExtracted() {
    PathTemplate t = PathTemplate.compile("/orgs/{org}/repos/{repo}");
    var m = t.match("/orgs/acme/repos/widget").orElseThrow();
    assertThat(m).containsEntry("org", "acme").containsEntry("repo", "widget");
  }

  @Test
  void doesNotMatchSlashesInsideParam() {
    assertThat(PathTemplate.compile("/users/{id}").match("/users/42/foo")).isEmpty();
  }

  @Test
  void rawIsPreserved() {
    assertThat(PathTemplate.compile("/users/{id}").raw()).isEqualTo("/users/{id}");
  }
}
