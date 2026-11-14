import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:supabase_flutter/supabase_flutter.dart';
import 'data/repository.dart';
import 'data/secure_store.dart';
import 'ui/journal.dart';
import 'ui/login.dart';
import 'ui/settings.dart';
import 'ui/theme.dart';
import 'startup.dart';
import 'startup_trace.dart';

const supabaseUrl = String.fromEnvironment(
  'SUPABASE_URL',
  defaultValue: 'https://lokvjxxtnnzxnkjiehja.supabase.co',
);
const publishableKey = String.fromEnvironment(
  'SUPABASE_PUBLISHABLE_KEY',
  defaultValue: 'sb_publishable_5QiXYbosgqwkhzKXPfarDQ_eS3y_-2L',
);
const secureStore = SecureStore();
final authProvider = StreamProvider<Session?>((ref) async* {
  final auth = Supabase.instance.client.auth;
  yield auth.currentSession;
  yield* auth.onAuthStateChange.map((event) => event.session);
});
final repositoryProvider = Provider<DoranRepository>((ref) {
  final ownerId =
      ref.watch(authProvider.select((state) => state.asData?.value?.user.id)) ??
      '';
  final client = http.Client();
  ref.onDispose(client.close);
  final auth = Supabase.instance.client.auth;
  return DoranRepository(
    client: client,
    ownerId: ownerId,
    token: () async {
      var session = auth.currentSession;
      if (session?.isExpired ?? false) {
        try {
          session = (await auth.refreshSession()).session;
        } on AuthRetryableFetchException {
          throw http.ClientException('인증 서버 연결을 확인해 주세요');
        }
      }
      return session?.accessToken;
    },
    userId: () => auth.currentUser?.id ?? '',
    cache: secureStore,
    baseUrl: '$supabaseUrl/functions/v1/doran-api/v1',
    publishableKey: publishableKey,
  );
});
final appInitialization = AppInitialization(
  initialize: _initializeSupabase,
  cleanupAfterFailure: _disposeSupabaseAfterFailure,
);

Future<void> _initializeSupabase() async {
  traceStartup(StartupStage.supabaseBegin);
  try {
    await Supabase.initialize(
      url: supabaseUrl,
      publishableKey: publishableKey,
      debug: false,
      authOptions: FlutterAuthClientOptions(
        authFlowType: AuthFlowType.pkce,
        localStorage: const SecureSessionStorage(secureStore),
        pkceAsyncStorage: SecurePkceStorage(secureStore),
      ),
    );
    traceStartup(StartupStage.supabaseComplete);
  } catch (_) {
    traceStartup(StartupStage.supabaseFailure);
    rethrow;
  }
}

Future<void> _disposeSupabaseAfterFailure() async {
  traceStartup(StartupStage.cleanupBegin);
  try {
    Supabase? instance;
    try {
      instance = Supabase.instance;
    } on AssertionError {
      // In debug mode the SDK rejects the getter if initialization failed
      // before constructing its client. There is no initialized client to reset.
    }
    if (instance?.isInitialized == true) await instance!.dispose();
    traceStartup(StartupStage.cleanupComplete);
  } catch (_) {
    traceStartup(StartupStage.cleanupFailure);
    rethrow;
  }
}

void main() {
  traceStartup(StartupStage.dartMain);
  WidgetsFlutterBinding.ensureInitialized();
  LicenseRegistry.addLicense(() async* {
    yield LicenseEntryWithLineBreaks(const [
      'Google Sans',
    ], await rootBundle.loadString('assets/google/OFL.txt'));
  });
  runApp(
    AppBootstrap(
      initialization: appInitialization,
      child: const ProviderScope(child: DoranApp()),
    ),
  );
  if (kDebugMode) {
    unawaited(
      WidgetsBinding.instance.waitUntilFirstFrameRasterized.then(
        (_) => traceStartup(StartupStage.firstFrameRasterized),
      ),
    );
  }
}

class DoranApp extends ConsumerWidget {
  const DoranApp({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(authProvider);
    return DoranMaterialApp(
      ownerId: session.asData?.value?.user.id ?? 'signed-out',
      home: session.when(
        data: (s) => s == null
            ? LoginPage(
                onLogin: () => Supabase.instance.client.auth.signInWithOAuth(
                  OAuthProvider.google,
                  redirectTo: 'app.doran.mobile://auth-callback',
                  authScreenLaunchMode: LaunchMode.externalApplication,
                ),
              )
            : JournalShell(key: ValueKey(s.user.id)),
        loading: () =>
            const Scaffold(body: Center(child: CircularProgressIndicator())),
        error: (_, _) => LoginPage(
          authError: '로그인을 확인하지 못했어요. 다시 연결해 주세요.',
          onLogin: () => Supabase.instance.client.auth.signInWithOAuth(
            OAuthProvider.google,
            redirectTo: 'app.doran.mobile://auth-callback',
            authScreenLaunchMode: LaunchMode.externalApplication,
          ),
        ),
      ),
    );
  }
}

/// Rebuild the Navigator itself when account ownership changes. A home key
/// alone would leave pushed editor routes from the prior account alive.
class DoranMaterialApp extends StatelessWidget {
  final String? ownerId;
  final Widget home;
  const DoranMaterialApp({
    super.key,
    required this.ownerId,
    required this.home,
  });
  @override
  Widget build(BuildContext context) => MaterialApp(
    key: ValueKey('doran-navigator:$ownerId'),
    title: '도란',
    debugShowCheckedModeBanner: false,
    theme: doranTheme(),
    locale: const Locale('ko'),
    supportedLocales: const [Locale('ko'), Locale('en')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    home: home,
  );
}

class JournalShell extends ConsumerStatefulWidget {
  const JournalShell({super.key});
  @override
  ConsumerState<JournalShell> createState() => _JournalShellState();
}

class _JournalShellState extends ConsumerState<JournalShell>
    with WidgetsBindingObserver {
  int selected = 0, revision = 0;
  RealtimeChannel? channel;
  Timer? debounce;
  Timer? refreshTimer;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    refreshTimer = Timer.periodic(
      const Duration(seconds: 30),
      (_) => _refresh(),
    );
    final user = Supabase.instance.client.auth.currentUser!;
    channel = Supabase.instance.client
        .channel('mobile-journal-${user.id}')
        .onPostgresChanges(
          event: PostgresChangeEvent.all,
          schema: 'public',
          table: 'records',
          filter: PostgresChangeFilter(
            type: PostgresChangeFilterType.eq,
            column: 'owner_id',
            value: user.id,
          ),
          callback: (_) => _refresh(),
        )
        .onPostgresChanges(
          event: PostgresChangeEvent.all,
          schema: 'public',
          table: 'days',
          filter: PostgresChangeFilter(
            type: PostgresChangeFilterType.eq,
            column: 'owner_id',
            value: user.id,
          ),
          callback: (_) => _refresh(),
        )
        .subscribe();
  }

  void _refresh() {
    debounce?.cancel();
    debounce = Timer(const Duration(milliseconds: 500), () {
      if (mounted) setState(() => revision++);
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refresh();
      refreshTimer ??= Timer.periodic(
        const Duration(seconds: 30),
        (_) => _refresh(),
      );
    } else {
      refreshTimer?.cancel();
      refreshTimer = null;
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    debounce?.cancel();
    refreshTimer?.cancel();
    final c = channel;
    if (c != null) Supabase.instance.client.removeChannel(c);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final repository = ref.watch(repositoryProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('도란')),
      body: selected == 2
          ? SettingsPage(
              repository: repository,
              onSignOut: () async {
                final ownerPrefix = repository.prefix;
                try {
                  await Supabase.instance.client.auth.signOut(
                    scope: SignOutScope.local,
                  );
                } finally {
                  await secureStore.clearPrefix(ownerPrefix);
                }
              },
              onDeleteAccount: () async {
                await repository.deleteAccount();
                await Supabase.instance.client.auth.signOut(
                  scope: SignOutScope.local,
                );
              },
            )
          : JournalPage(
              key: ValueKey(selected),
              refreshRevision: revision,
              repository: repository,
              history: selected == 1,
            ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: selected,
        onDestinationSelected: (i) => setState(() => selected = i),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.wb_sunny_outlined),
            selectedIcon: Icon(Icons.wb_sunny),
            label: '오늘',
          ),
          NavigationDestination(
            icon: Icon(Icons.menu_book_outlined),
            selectedIcon: Icon(Icons.menu_book),
            label: '기록',
          ),
          NavigationDestination(
            icon: Icon(Icons.tune_outlined),
            selectedIcon: Icon(Icons.tune),
            label: '설정',
          ),
        ],
      ),
    );
  }
}
