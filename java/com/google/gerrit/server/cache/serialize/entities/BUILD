load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "entities",
    srcs = glob(["*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/gerrit/entities",
        "//java/com/google/gerrit/extensions:api",
        "//java/com/google/gerrit/server/cache/serialize",
        "//lib:guava",
        "//lib:jgit",
        "//lib:protobuf",
        "//lib/errorprone:annotations",
        "//proto:cache_java_proto",
    ],
)
