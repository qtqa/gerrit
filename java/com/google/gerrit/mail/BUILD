load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "mail",
    srcs = glob(["*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/gerrit/common:annotations",
        "//java/com/google/gerrit/entities",
        "//lib:guava",
        "//lib/auto:auto-value",
        "//lib/auto:auto-value-annotations",
        "//lib/errorprone:annotations",
        "//lib/flogger:api",
        "//lib/jsoup",
        "//lib/mime4j:core",
        "//lib/mime4j:dom",
    ],
)
