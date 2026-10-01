import 'package:flutter/material.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/l10n/app_strings.dart';

class DesktopToolsScreen extends StatefulWidget {
  const DesktopToolsScreen({super.key});

  @override
  State<DesktopToolsScreen> createState() => _DesktopToolsScreenState();
}

class _DesktopToolsScreenState extends State<DesktopToolsScreen>
    with SingleTickerProviderStateMixin {
  late final TabController _tabs;
  final _dockSearchController = TextEditingController();
  List<Map<String, dynamic>> _androidApps = const [];
  List<String> _dockPackages = const [];
  List<Map<String, dynamic>> _snapshots = const [];
  bool _dockBusy = true;
  bool _snapshotBusy = false;

  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 2, vsync: this);
    _loadDock();
    _loadSnapshots();
  }

  @override
  void dispose() {
    _dockSearchController.dispose();
    _tabs.dispose();
    super.dispose();
  }

  Future<void> _loadDock() async {
    final values = await Future.wait([
      DroidDeskPlatform.getAndroidApps(),
      DroidDeskPlatform.getDockPackages(),
    ]);
    if (!mounted) return;
    setState(() {
      _androidApps = values[0] as List<Map<String, dynamic>>;
      _dockPackages = values[1] as List<String>;
      _dockBusy = false;
    });
  }

  Future<void> _saveDock(List<String> packages) async {
    setState(() {
      _dockPackages = packages;
      _dockBusy = true;
    });
    final saved = await DroidDeskPlatform.saveDockPackages(packages);
    if (!mounted) return;
    setState(() => _dockBusy = false);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(saved ? l10n.dockUpdated : l10n.dockUpdateFailed)),
    );
  }

  Future<void> _loadSnapshots() async {
    final snapshots = await DroidDeskPlatform.listDesktopSnapshots();
    if (mounted) setState(() => _snapshots = snapshots);
  }

  Future<void> _createSnapshot() async {
    setState(() => _snapshotBusy = true);
    try {
      await DroidDeskPlatform.createDesktopSnapshot();
      await _loadSnapshots();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(l10n.snapshotCreated)),
        );
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l10n.snapshotFailed('$error'))));
      }
    } finally {
      if (mounted) setState(() => _snapshotBusy = false);
    }
  }

  Future<void> _restoreSnapshot(String name) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.restoreSnapshotTitle),
        content: Text(l10n.restoreSnapshotMessage),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(l10n.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(l10n.restore),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _snapshotBusy = true);
    try {
      final restored = await DroidDeskPlatform.restoreDesktopSnapshot(name);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(restored ? l10n.snapshotRestored : l10n.restoreFailed),
          ),
        );
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l10n.restoreFailedWithError('$error'))));
      }
    } finally {
      if (mounted) setState(() => _snapshotBusy = false);
    }
  }

  Future<void> _deleteSnapshot(String name) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.deleteSnapshotTitle),
        content: Text(name),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(l10n.cancel),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(l10n.delete),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      await DroidDeskPlatform.deleteDesktopSnapshot(name);
      await _loadSnapshots();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(l10n.desktopTools),
        bottom: TabBar(
          controller: _tabs,
          isScrollable: true,
          tabs: [
            Tab(icon: const Icon(Icons.dock_rounded), text: l10n.tabDock),
            Tab(icon: const Icon(Icons.restore_rounded), text: l10n.tabBackups),
          ],
        ),
      ),
      body: Container(
        decoration: BoxDecoration(
          gradient: DroidTheme.backgroundGradient,
        ),
        child: TabBarView(
          controller: _tabs,
          children: [_dockTab(), _backupsTab()],
        ),
      ),
    );
  }

  Widget _dockTab() {
    if (_dockBusy && _androidApps.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    final byPackage = {
      for (final app in _androidApps) app['package'].toString(): app,
    };
    final query = _dockSearchController.text.trim().toLowerCase();
    final available = _androidApps
        .where((app) {
          if (_dockPackages.contains(app['package'])) return false;
          return query.isEmpty ||
              app['label'].toString().toLowerCase().contains(query) ||
              app['package'].toString().toLowerCase().contains(query);
        })
        .take(30)
        .toList();
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          children: [
            Text(l10n.pinnedSection, style: DroidTheme.label),
            const Spacer(),
            if (_dockBusy)
              const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            const SizedBox(width: 8),
            Text('${_dockPackages.length}/8', style: DroidTheme.bodySm),
          ],
        ),
        const SizedBox(height: 8),
        ReorderableListView.builder(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          itemCount: _dockPackages.length,
          onReorderItem: (oldIndex, newIndex) {
            final next = [..._dockPackages];
            final value = next.removeAt(oldIndex);
            next.insert(newIndex, value);
            _saveDock(next);
          },
          itemBuilder: (context, index) {
            final package = _dockPackages[index];
            final app = byPackage[package];
            return Card(
              key: ValueKey(package),
              child: ListTile(
                leading: const Icon(Icons.drag_handle_rounded),
                title: Text(app?['label']?.toString() ?? package),
                subtitle: Text(package),
                trailing: IconButton(
                  icon: const Icon(Icons.close_rounded),
                  onPressed: _dockBusy
                      ? null
                      : () => _saveDock([..._dockPackages]..remove(package)),
                ),
              ),
            );
          },
        ),
        const SizedBox(height: 20),
        Text(l10n.addAndroidAppSection, style: DroidTheme.label),
        const SizedBox(height: 8),
        TextField(
          controller: _dockSearchController,
          onChanged: (_) => setState(() {}),
          decoration: InputDecoration(
            prefixIcon: const Icon(Icons.search),
            hintText: l10n.searchInstalledApps,
          ),
        ),
        const SizedBox(height: 8),
        ...available.map(
          (app) => ListTile(
            title: Text(app['label'].toString()),
            subtitle: Text(app['package'].toString()),
            trailing: IconButton(
              icon: const Icon(Icons.add_circle_outline_rounded),
              onPressed: _dockBusy || _dockPackages.length >= 8
                  ? null
                  : () => _saveDock([
                      ..._dockPackages,
                      app['package'].toString(),
                    ]),
            ),
          ),
        ),
      ],
    );
  }

  Widget _backupsTab() {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        FilledButton.icon(
          onPressed: _snapshotBusy ? null : _createSnapshot,
          icon: _snapshotBusy
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.add_rounded),
          label: Text(l10n.createSnapshot),
        ),
        const SizedBox(height: 12),
        Text(
          l10n.createSnapshotDescription,
          style: DroidTheme.bodySm,
        ),
        const SizedBox(height: 20),
        Text(l10n.snapshotsSection, style: DroidTheme.label),
        const SizedBox(height: 8),
        if (_snapshots.isEmpty)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Center(child: Text(l10n.noSnapshots)),
            ),
          ),
        ..._snapshots.map((snapshot) {
          final created = DateTime.fromMillisecondsSinceEpoch(
            (snapshot['created'] as num).toInt(),
          );
          return Card(
            child: ListTile(
              leading: const Icon(Icons.inventory_2_outlined),
              title: Text(snapshot['name'].toString()),
              subtitle: Text(
                '${created.toLocal()} · ${_formatBytes((snapshot['sizeBytes'] as num).toInt())}',
              ),
              trailing: PopupMenuButton<String>(
                onSelected: (value) => value == 'restore'
                    ? _restoreSnapshot(snapshot['name'].toString())
                    : _deleteSnapshot(snapshot['name'].toString()),
                itemBuilder: (_) => [
                  PopupMenuItem(value: 'restore', child: Text(l10n.restore)),
                  PopupMenuItem(value: 'delete', child: Text(l10n.delete)),
                ],
              ),
            ),
          );
        }),
      ],
    );
  }

  String _formatBytes(int bytes) {
    if (bytes >= 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
    }
    if (bytes >= 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    }
    return '${(bytes / 1024).toStringAsFixed(1)} KB';
  }
}
