import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/state/app_state.dart';

/// Drives the real AppState against a fake 'com.droiddesk/core' channel.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.droiddesk/core');

  late List<MethodCall> calls;
  late Map<String, dynamic> runtimeStatus;
  late bool statusFails;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    calls = [];
    statusFails = false;
    runtimeStatus = {
      'isBootstrapped': true,
      'isRunning': false,
      'hasRoot': false,
      'distro': 'termux-native',
      'installedDE': 'xfce4',
    };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'getRuntimeStatus':
          if (statusFails) throw PlatformException(code: 'runtime_status');
          return runtimeStatus;
        case 'getOptionalApps':
          return {'proot_debian': true};
        case 'getDeviceInfo':
          return <String, dynamic>{};
        case 'startLinux':
          return true;
        case 'executeCommand':
          return '';
        case 'startContainerShell':
          // PRoot fails the way it does on the phone.
          DroidDeskPlatform.onContainerOutput
              ?.call('execve(...): Operation not permitted\n');
          return 1;
        case 'containerInput':
          return false;
        default:
          return null;
      }
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  Future<AppState> ready() async {
    final state = AppState();
    await state.initialize();
    calls.clear();
    return state;
  }

  List<String> methods() => calls.map((c) => c.method).toList();

  group('PRoot container is isolated from the native terminal', () {
    test('a PRoot failure does not clear or dirty the native buffer', () async {
      final state = await ready();
      await state.executeCommand('echo nativo');
      final nativeBefore = List<String>.from(state.terminalOutput);

      await state.startDebianShell();
      expect(state.isProotTerminal, isTrue);
      expect(state.terminalOutput.join(), contains('Operation not permitted'));

      state.useNativeTerminal();
      expect(state.terminalOutput, nativeBefore);
      expect(state.terminalOutput.join(), isNot(contains('Operation not permitted')));
    });

    test('the container never goes through the native command channel', () async {
      final state = await ready();
      await state.startDebianShell();
      await state.executeCommand('ls');
      await state.interruptCommand();

      expect(methods(), containsAllInOrder(['startContainerShell', 'containerInput', 'stopContainerShell']));
      expect(methods(), isNot(contains('executeCommand')));
      expect(methods(), isNot(contains('interruptCommand')));
      expect(methods(), isNot(contains('stopLinux')));
      expect(calls.where((c) => c.method == 'containerInput').single.arguments['command'], 'ls');
    });

    test('a PRoot failure does not change the native desktop state', () async {
      runtimeStatus['isRunning'] = true;
      final state = await ready();
      expect(state.isRunning, isTrue);
      await state.startDebianShell();
      expect(state.isRunning, isTrue);
      expect(methods(), isNot(contains('stopLinux')));
    });

    test('closing the Debian terminal leaves the native terminal usable', () async {
      final state = await ready();
      await state.startDebianShell();
      state.useNativeTerminal();
      await state.executeCommand('pwd');
      expect(calls.last.method, 'executeCommand');
      expect(calls.last.arguments['command'], 'pwd');
    });
  });

  group('"Escritorio activo" comes from the runtime', () {
    test('pressing Iniciar does not turn the card green by itself', () async {
      final state = await ready();
      await state.startLinux();
      expect(methods(), containsAllInOrder(['startLinux', 'getRuntimeStatus']));
      expect(state.isRunning, isFalse);
    });

    test('active only when the runtime reports it', () async {
      final state = await ready();
      runtimeStatus['isRunning'] = true;
      await state.refreshStatus();
      expect(state.isRunning, isTrue);
    });

    test('a failed status refresh never stays green', () async {
      runtimeStatus['isRunning'] = true;
      final state = await ready();
      expect(state.isRunning, isTrue);
      statusFails = true;
      await state.refreshStatus();
      expect(state.isRunning, isFalse);
    });
  });
}
