load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "project-test-util",
    testonly = True,
    srcs = glob(["*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/gerrit/entities",
        "//lib/errorprone:annotations",
    ],
)
