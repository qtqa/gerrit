load("@rules_java//java:defs.bzl", "java_library")

package(default_testonly = True)

java_library(
    name = "testing",
    srcs = glob(["*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "//lib:protobuf",
        "//lib/errorprone:annotations",
    ],
)
