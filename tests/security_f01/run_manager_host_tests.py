"""Compile F01 production Kotlin and tests without Gradle, Android, JNI or root.

Android annotations, native metadata constants/enums, HTTP plumbing and the
libsu receiver are stubbed.
The repository, JSON model, quoting helper, CLI method bodies and editor ID
validator are read from the checkout at execution time.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com/shixiaoshi0417/lunexsu"
MAIN = ROOT / "manager/app/src/main/java" / PACKAGE
TEST = ROOT / "manager/app/src/test/java" / PACKAGE


def one(pattern, directory):
    matches = sorted(directory.glob(pattern))
    if not matches:
        raise RuntimeError(f"Missing cached dependency: {directory}/{pattern}")
    return matches[-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default=shutil.which("java") or r"D:\Android Studio\jbr\bin\java.exe")
    parser.add_argument("--kotlin-lib", type=Path)
    parser.add_argument("--gradle-cache", type=Path, default=Path.home() / ".gradle")
    parser.add_argument("--temp", type=Path)
    args = parser.parse_args()
    cache = args.gradle_cache / "caches/modules-2/files-2.1"
    lib = args.kotlin_lib or one("wrapper/dists/gradle-*-bin/*/gradle-*/lib", args.gradle_cache)
    classpath = [
        one("kotlin-stdlib-*.jar", lib),
        one("kotlinx-coroutines-core-jvm-*.jar", lib),
        one("annotations-*.jar", lib),
        one("org.json/json/*/*/*.jar", cache),
        one("junit/junit/*/*/*.jar", cache),
        one("org.hamcrest/hamcrest-core/*/*/*.jar", cache),
    ]
    shell = os.environ.get("F01_TEST_SH") or shutil.which("sh")
    if not shell and Path(r"C:\Program Files\Git\bin\sh.exe").is_file():
        shell = r"C:\Program Files\Git\bin\sh.exe"
    if not shell:
        raise RuntimeError("Set F01_TEST_SH to a POSIX shell; argv checks must not be skipped")
    environment = dict(os.environ, F01_TEST_SH=shell)
    cli_source = (MAIN / "ui/util/KsuCli.kt").read_text(encoding="utf-8")
    cli_methods = cli_source[cli_source.index("fun isSepolicyValid("):cli_source.index("fun forceStopApp(")]
    editor_source = (MAIN / "ui/screen/templateeditor/TemplateEditorUtils.kt").read_text(encoding="utf-8")
    validator = re.search(r"fun isValidTemplateId\(id: String\): Boolean \{.*?\n\}", editor_source, re.S)
    if not validator:
        raise RuntimeError("Cannot locate production editor validator")
    stubs = {
        "Parcelable.kt": "package android.os\ninterface Parcelable\n",
        "Parcelize.kt": "package kotlinx.parcelize\nannotation class Parcelize\n",
        "Immutable.kt": "package androidx.compose.runtime\nannotation class Immutable\n",
        "Log.kt": """package android.util
object Log {
    fun i(tag: String, message: String) = 0
    fun e(tag: String, message: String, error: Throwable? = null) = 0
}
""",
        "Natives.kt": """package com.shixiaoshi0417.lunexsu
object Natives {
    const val ROOT_UID = 0
    const val ROOT_GID = 0
    const val KERNEL_SU_DOMAIN = "u:r:ksu:s0"
    class Profile {
        enum class Namespace { INHERITED, GLOBAL, INDIVIDUAL }
        enum class RootProfileFlag { NO_NEW_PRIVS }
    }
}
class HostApp { val okhttpClient = okhttp3.OkHttpClient() }
val ksuApp = HostApp()
""",
        "Http.kt": """package okhttp3
object HostHttp {
    val requests = mutableListOf<String>()
    private val responses = ArrayDeque<String>()
    fun reset(vararg values: String) { requests.clear(); responses.clear(); responses.addAll(values) }
    fun take(): String = responses.removeFirst()
}
class Request(val url: String) {
    class Builder {
        private var value = ""
        fun url(value: String): Builder { this.value = value; return this }
        fun build() = Request(value)
    }
}
class OkHttpClient {
    fun newCall(request: Request): Call { HostHttp.requests.add(request.url); return Call() }
}
class Call { fun execute() = Response(HostHttp.take()) }
class Response(value: String) : AutoCloseable {
    val isSuccessful = true
    val body = Body(value)
    override fun close() = Unit
}
class Body(private val value: String) { fun string(): String = value }
""",
        "ProfileCliUnderTest.kt": """package com.shixiaoshi0417.lunexsu.ui.util
import android.util.Log
private const val TAG = "KsuCli"
object ShellProbe {
    var opens = 0
    val commands = mutableListOf<String>()
    var output = emptyList<String>()
    fun reset() { opens = 0; commands.clear(); output = emptyList() }
}
class HostResult {
    val code = 0
    val isSuccess = true
    val out get() = ShellProbe.output
    val err = emptyList<String>()
}
class HostJob {
    fun add(command: String): HostJob { ShellProbe.commands.add(command); return this }
    fun to(out: MutableList<String>, err: MutableList<String>?) = this
    fun exec() = HostResult()
}
class HostShell { fun newJob() = HostJob() }
fun <T> withRootShell(block: HostShell.() -> T): T {
    ShellProbe.opens++
    return HostShell().block()
}
fun getKsuDaemonPath() = "f01_receiver"
""" + cli_methods + "\n" + validator.group(0),
    }
    with tempfile.TemporaryDirectory(prefix="lunexsu-f01-kotlin-", dir=args.temp) as tmp:
        output = Path(tmp)
        generated = []
        for name, body in stubs.items():
            path = output / name
            path.write_text(body, encoding="utf-8")
            generated.append(path)
        production = [MAIN / name for name in (
            "ui/util/ProfileCommand.kt", "data/repository/TemplateRepository.kt",
            "data/repository/TemplateRepositoryImpl.kt", "data/model/TemplateInfo.kt",
            "profile/Groups.kt", "profile/Capabilities.kt",
        )]
        tests = [TEST / "ui/util/ProfileCommandTest.kt", TEST / "data/repository/TemplateImportSecurityTest.kt",
                 ROOT / "tests/security_f01/ProfileCliHostTest.kt", ROOT / "tests/security_f01/TemplateRepositoryHostTest.kt"]
        classes = output / "classes"
        dependency_path = os.pathsep.join(map(str, classpath))
        subprocess.run([
            args.java, "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", dependency_path,
            "-d", str(classes), *map(str, production + tests + generated),
        ], check=True, env=environment)
        subprocess.run([
            args.java, f"-Djava.io.tmpdir={output}", "-cp", str(classes) + os.pathsep + dependency_path, "org.junit.runner.JUnitCore",
            "com.shixiaoshi0417.lunexsu.ui.util.ProfileCommandTest",
            "com.shixiaoshi0417.lunexsu.data.repository.TemplateImportSecurityTest",
            "com.shixiaoshi0417.lunexsu.ui.util.ProfileCliHostTest",
            "com.shixiaoshi0417.lunexsu.data.repository.TemplateRepositoryHostTest",
        ], check=True, env=environment)


if __name__ == "__main__":
    main()
