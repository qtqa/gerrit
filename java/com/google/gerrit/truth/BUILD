load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "truth",
    testonly = True,
    srcs = glob(["**/*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/gerrit/common:annotations",
        "//lib:guava",
        "//lib:jgit",
        "//lib/errorprone:annotations",
        "//lib/truth",
    ],
)
