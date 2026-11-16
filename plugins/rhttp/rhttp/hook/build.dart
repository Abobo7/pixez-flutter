import 'dart:io';

import 'package:flutter_rust_bridge_hooks/flutter_rust_bridge_hooks.dart';
import 'package:path/path.dart' as p;

void main(List<String> args) async {
  await build(args, (input, output) async {
    await FlutterRustBridgeNativeAssetsBuilder(
      cratePath: 'rust',
      extraCargoEnvironmentVariables: _cargoEnvironment(input),
    ).run(input: input, output: output);
  });
}

String? _findNdk25Root() {
  final env = Platform.environment;
  final sdkRoot = env['ANDROID_SDK_ROOT'] ?? env['ANDROID_HOME'];
  final candidates = <String?>[
    env['ANDROID_NDK_R25_HOME'],
    env['ANDROID_NDK_HOME'],
    env['ANDROID_NDK_ROOT'],
    if (sdkRoot != null && sdkRoot.isNotEmpty) '$sdkRoot/ndk/25.2.9519653',
  ];
  for (final raw in candidates) {
    if (raw == null || raw.isEmpty) continue;
    final norm = raw.replaceAll(r'\', '/');
    if (Directory('$norm/toolchains/llvm/prebuilt').existsSync()) {
      return norm;
    }
  }
  return null;
}

Map<String, String> _cargoEnvironment(BuildInput input) {
  final codeAssets = _codeAssets(input.config.json);
  final targetOs = codeAssets?['target_os'];
  final targetArch = codeAssets?['target_architecture'];
  final packageRoot = Directory.fromUri(input.packageRoot).path;
  final repoRoot = p.normalize(p.join(packageRoot, '..', '..', '..'));
  final compatDir = p
      .join(repoRoot, 'android', 'kitkat-prebuilt', 'armeabi-v7a')
      .replaceAll(r'\', '/');

  final env = <String, String>{};

  // Ensure Windows host build.rs scripts find Scoop PortableBuildTools MSVC link.exe
  // ahead of Git Bash's /usr/bin/link.exe when present.
  if (Platform.isWindows) {
    final scoopRoot = Platform.environment['SCOOP'];
    if (scoopRoot != null && scoopRoot.isNotEmpty) {
      final btRoot = '$scoopRoot\\persist\\portable-build-tools\\BuildTools';
      final vcTools = Directory('$btRoot\\VC\\Tools\\MSVC');
      final sdkDir = '$btRoot\\Windows Kits\\10';
      if (vcTools.existsSync() && Directory('$sdkDir\\Lib').existsSync()) {
        final msvcDirs = vcTools.listSync().whereType<Directory>().toList()
          ..sort((a, b) => a.path.compareTo(b.path));
        final sdkVers = Directory('$sdkDir\\Lib')
            .listSync()
            .whereType<Directory>()
            .map((d) => p.basename(d.path))
            .where((v) => v.startsWith('10.'))
            .toList()
          ..sort();
        if (msvcDirs.isNotEmpty && sdkVers.isNotEmpty) {
          final vcDir = msvcDirs.last.path;
          final sdkVer = sdkVers.last;
          final vcBin = '$vcDir\\bin\\Hostx64\\x64';
          final sdkBin =
              '$sdkDir\\bin\\$sdkVer\\x64;$sdkDir\\bin\\$sdkVer\\x64\\ucrt';
          env['INCLUDE'] =
              '$vcDir\\include;$sdkDir\\Include\\$sdkVer\\ucrt;$sdkDir\\Include\\$sdkVer\\shared;$sdkDir\\Include\\$sdkVer\\um;$sdkDir\\Include\\$sdkVer\\winrt';
          env['LIB'] =
              '$vcDir\\lib\\x64;$sdkDir\\Lib\\$sdkVer\\ucrt\\x64;$sdkDir\\Lib\\$sdkVer\\um\\x64';
          env['PATH'] = '$vcBin;$sdkBin;${Platform.environment['PATH'] ?? ''}';
          env['CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER'] =
              '$vcBin\\link.exe';
        }
      }
    }
  }

  // Scope target-specific linker flags via CARGO_TARGET_<TRIPLE>_RUSTFLAGS so
  // host build.rs executables (x86_64-pc-windows-msvc) never receive ELF flags.
  final baseFlags = <String>['--cfg', 'reqwest_unstable'];
  final home =
      Platform.environment['HOME'] ?? Platform.environment['USERPROFILE'];
  if (home != null && home.isNotEmpty) {
    baseFlags.add('--remap-path-prefix=$home/.cargo/=/.cargo/');
  }

  if (targetOs == 'android' && targetArch == 'arm') {
    final armFlags = <String>[
      ...baseFlags,
      '-C',
      'link-arg=--target=armv7a-linux-androideabi19',
      '-C',
      'link-arg=-Wl,--hash-style=both',
      if (Directory(compatDir).existsSync()) ...[
        '-C',
        'link-arg=-Wl,--no-as-needed',
        '-C',
        'link-arg=-L$compatDir',
        '-C',
        'link-arg=-ljnicompat19',
      ],
    ];
    env['CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_RUSTFLAGS'] = armFlags.join(' ');

    final ndkRoot = _findNdk25Root();
    if (ndkRoot != null) {
      final hostTag = Platform.isWindows
          ? 'windows-x86_64'
          : (Platform.isMacOS ? 'darwin-x86_64' : 'linux-x86_64');
      final exeExt = Platform.isWindows ? '.exe' : '';
      final cmdExt = Platform.isWindows ? '.cmd' : '';
      final llvmRoot = '$ndkRoot/toolchains/llvm/prebuilt/$hostTag';
      final ndkBin = '$llvmRoot/bin';
      final clangExe = '$ndkBin/clang$exeExt';
      if (File(clangExe).existsSync()) {
        final sysroot = '$llvmRoot/sysroot';
        env['CC_armv7_linux_androideabi'] =
            '$ndkBin/armv7a-linux-androideabi19-clang$cmdExt';
        env['CXX_armv7_linux_androideabi'] =
            '$ndkBin/armv7a-linux-androideabi19-clang++$cmdExt';
        // Point Cargo linker directly to clang binary (not .cmd) so cmd.exe never
        // mangles -Wl,--version-script=...= arguments on Windows.
        env['CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER'] = clangExe;
        env['AR_armv7_linux_androideabi'] = '$ndkBin/llvm-ar$exeExt';
        env['RANLIB_armv7_linux_androideabi'] = '$ndkBin/llvm-ranlib$exeExt';
        env['BINDGEN_EXTRA_CLANG_ARGS_armv7_linux_androideabi'] =
            '--sysroot=$sysroot -I$sysroot/usr/include/arm-linux-androideabi -D__ANDROID_API__=19';
        env['ANDROID_NATIVE_API_LEVEL'] = '19';
      }
    }
  } else if (targetOs == 'android' &&
      (targetArch == 'arm64' || targetArch == 'x64')) {
    final flags64 = <String>[
      ...baseFlags,
      '-C',
      'link-arg=-Wl,--hash-style=both',
      '-C',
      'link-arg=-Wl,-z,max-page-size=16384',
    ];
    final key = targetArch == 'arm64'
        ? 'CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS'
        : 'CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS';
    env[key] = flags64.join(' ');
  } else {
    env['RUSTFLAGS'] = baseFlags.join(' ');
  }

  if (targetOs == 'ios') {
    const version = '15.0';
    env['IPHONEOS_DEPLOYMENT_TARGET'] = version;
    env['IPHONESIMULATOR_DEPLOYMENT_TARGET'] = version;
  }

  return env;
}

Map<String, Object?>? _codeAssets(Map<String, Object?> config) {
  final extensions = config['extensions'];
  if (extensions is! Map) return null;
  final codeAssets = extensions['code_assets'];
  if (codeAssets is! Map) return null;
  return codeAssets.map((key, value) => MapEntry(key.toString(), value));
}
