package app.orionmd.marketdata

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.orionmd.marketdata.data.Catalog
import app.orionmd.marketdata.data.Net
import app.orionmd.marketdata.data.Prefs
import app.orionmd.marketdata.data.Stream
import app.orionmd.marketdata.pdf.ReportKind
import app.orionmd.marketdata.ui.MarketTheme
import app.orionmd.marketdata.ui.WatermarkBackground
import app.orionmd.marketdata.ui.components.LocalNav
import app.orionmd.marketdata.ui.components.Nav
import app.orionmd.marketdata.ui.fmtTime
import app.orionmd.marketdata.ui.screens.*
import kotlinx.coroutines.delay

class MainActivity : FragmentActivity() {
    private var pendingIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingIntent = intent
        setContent {
            val settings by Prefs.settings.collectAsState()
            MarketTheme(settings) {
                var showSplash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
                LaunchedEffect(Unit) { delay(1600); showSplash = false }
                Box(Modifier.fillMaxSize()) {
                    WatermarkBackground(settings.watermarkAlpha) { AppRoot(pendingIntent) { pendingIntent = null } }
                    AnimatedVisibility(showSplash, enter = fadeIn(), exit = fadeOut()) { Splash() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingIntent = intent
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize().background(Color(0xFF05091F)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.splash_full), "OrionMD", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        CircularProgressIndicator(Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).size(22.dp), color = Color(0xFF3FC8F5), strokeWidth = 2.dp)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("dashboard", "Dashboard", Icons.Default.Dashboard),
    Tab("markets", "Markets", Icons.AutoMirrored.Filled.ShowChart),
    Tab("crypto", "Crypto", Icons.Default.CurrencyBitcoin),
    Tab("watchlists", "Watchlists", Icons.Default.Star),
    Tab("news", "News", Icons.Default.Newspaper),
)

private data class MoreItem(val route: String, val label: String, val icon: ImageVector)

private val moreItems = listOf(
    MoreItem("portfolio", "Portfolio", Icons.Default.AccountBalanceWallet),
    MoreItem("paper", "Paper trading", Icons.Default.School),
    MoreItem("alerts", "Price alerts", Icons.Default.NotificationsActive),
    MoreItem("compare", "Compare symbols", Icons.AutoMirrored.Filled.CompareArrows),
    MoreItem("screener", "Stock screener", Icons.Default.FilterList),
    MoreItem("calendars", "Calendars", Icons.Default.CalendarMonth),
    MoreItem("economy", "Economy", Icons.Default.AccountBalance),
    MoreItem("global", "Global markets", Icons.Default.Public),
    MoreItem("converter", "Converter", Icons.Default.CurrencyExchange),
    MoreItem("exchanges", "Crypto exchanges", Icons.Default.Storefront),
    MoreItem("notes", "Notes", Icons.Default.EditNote),
    MoreItem("reports", "PDF reports", Icons.Default.PictureAsPdf),
    MoreItem("backup", "Backup & export", Icons.Default.Backup),
    MoreItem("settings", "Settings", Icons.Default.Settings),
)

private fun titleFor(route: String?, arg: String?): String = when {
    route == null -> "Market_Data"
    route.startsWith("symbol") -> Catalog.display(arg ?: "")
    route.startsWith("pdf/") -> "PDF options"
    route.startsWith("viewer") -> "PDF viewer"
    route.startsWith("movers") -> "Top movers"
    route.startsWith("filings") -> "SEC filings"
    route == "search" -> "Search"
    else -> tabs.firstOrNull { it.route == route }?.label ?: moreItems.firstOrNull { it.route == route }?.label ?: "Market_Data"
}

/** Which report the PDF button makes on each screen. */
private fun reportFor(route: String?, arg: String?): Pair<ReportKind, String?> = when {
    route == null -> ReportKind.MARKET to null
    route.startsWith("symbol") -> ReportKind.SYMBOL to arg
    route == "crypto" || route == "exchanges" -> ReportKind.CRYPTO to null
    route == "watchlists" -> ReportKind.WATCHLIST to null
    route == "news" -> ReportKind.NEWS to null
    route == "portfolio" -> ReportKind.PORTFOLIO to null
    route == "paper" -> ReportKind.PAPER to null
    route == "calendars" -> ReportKind.CALENDARS to null
    route == "economy" -> ReportKind.ECONOMY to null
    route == "screener" || route.startsWith("movers") -> ReportKind.MOVERS to null
    route == "compare" -> ReportKind.COMPARE to null
    route == "global" -> ReportKind.GLOBAL to null
    else -> ReportKind.MARKET to null
}

@Composable
private fun AppRoot(intent: Intent?, consumed: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore("/{")?.substringBefore("?")
    val fullRoute = entry?.destination?.route
    val arg = entry?.arguments?.getString("sym")
    val isTab = tabs.any { it.route == route }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    var menu by remember { mutableStateOf(false) }
    val offline by Net.offlineSince.collectAsState()
    val live by Stream.connected.collectAsState()

    val navApi = remember(nav) {
        object : Nav {
            override fun go(route: String) { nav.navigate(route) { launchSingleTop = true } }
            override fun back() { nav.popBackStack() }
        }
    }

    // notification permission (alerts & summaries)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }

    // deep links from notifications
    LaunchedEffect(intent) {
        intent ?: return@LaunchedEffect
        intent.getStringExtra("symbol")?.let { navApi.symbol(it) }
        intent.getStringExtra("pdf")?.let { navApi.go("viewer?path=${Uri.encode(it)}&temp=false") }
        consumed()
    }

    CompositionLocalProvider(LocalNav provides navApi) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        if (!isTab) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        else Image(painterResource(R.mipmap.ic_launcher_round), null, Modifier.padding(start = 12.dp, end = 4.dp).size(30.dp))
                    },
                    title = {
                        Column {
                            Text(titleFor(route, arg), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            when {
                                offline != null -> Text("Offline · showing data saved ${fmtTime(offline!!, "h:mm a")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                live -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    app.orionmd.marketdata.ui.components.Dot(app.orionmd.marketdata.ui.Up, 6.dp)
                                    Text(" Live", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { navApi.go("search") }) { Icon(Icons.Default.Search, "Search") }
                        if (fullRoute?.startsWith("pdf/") != true && fullRoute?.startsWith("viewer") != true) IconButton(onClick = {
                            val (k, a) = reportFor(route, arg)
                            navApi.go("pdf/${k.name}?arg=${Uri.encode(a ?: "")}")
                        }) { Icon(Icons.Default.PictureAsPdf, "PDF report") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                moreItems.forEach { m ->
                                    DropdownMenuItem(text = { Text(m.label) }, leadingIcon = { Icon(m.icon, null) }, onClick = { menu = false; navApi.go(m.route) })
                                }
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.navigate(t.route) { popUpTo("dashboard") { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = { Icon(t.icon, t.label) }, label = { Text(t.label, maxLines = 1) },
                        )
                    }
                }
            },
        ) { pad ->
            Row(Modifier.padding(pad).fillMaxSize()) {
                if (wide) NavigationRail(containerColor = Color.Transparent) {
                    tabs.forEach { t ->
                        NavigationRailItem(selected = route == t.route,
                            onClick = { nav.navigate(t.route) { popUpTo("dashboard") { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = { Icon(t.icon, t.label) }, label = { Text(t.label) })
                    }
                    Spacer(Modifier.weight(1f))
                    NavigationRailItem(selected = route == "portfolio", onClick = { navApi.go("portfolio") }, icon = { Icon(Icons.Default.AccountBalanceWallet, null) }, label = { Text("Portfolio") })
                }
                Box(Modifier.weight(1f)) { Routes(nav) }
            }
        }
    }
}

@Composable
private fun Routes(nav: NavHostController) {
    NavHost(nav, startDestination = "dashboard") {
        composable("dashboard") { DashboardScreen() }
        composable("markets") { MarketsScreen() }
        composable("crypto") { CryptoScreen() }
        composable("watchlists") { WatchlistsScreen() }
        composable("news") { NewsScreen() }
        composable("symbol/{sym}", arguments = listOf(navArgument("sym") { type = NavType.StringType })) {
            SymbolScreen(Uri.decode(it.arguments?.getString("sym") ?: "AAPL"))
        }
        composable("search") { SearchScreen() }
        composable("compare?syms={syms}", arguments = listOf(navArgument("syms") { type = NavType.StringType; defaultValue = "" })) {
            CompareScreen(it.arguments?.getString("syms").orEmpty().split(",").filter { s -> s.isNotBlank() })
        }
        composable("portfolio") { LockGate { PortfolioScreen() } }
        composable("paper") { LockGate { PaperScreen() } }
        composable("alerts") { AlertsScreen() }
        composable("calendars") { CalendarsScreen() }
        composable("screener") { ScreenerScreen() }
        composable("economy") { EconomyScreen() }
        composable("global") { GlobalScreen() }
        composable("converter") { ConverterScreen() }
        composable("exchanges") { ExchangesScreen() }
        composable("notes") { NotesScreen() }
        composable("settings") { SettingsScreen() }
        composable("backup") { BackupScreen() }
        composable("reports") { ReportsScreen() }
        composable("movers/{kind}") { MoversScreen(it.arguments?.getString("kind") ?: "GAINERS") }
        composable("filings/{sym}") { FilingsScreen(Uri.decode(it.arguments?.getString("sym") ?: "")) }
        composable("pdf/{kind}?arg={arg}", arguments = listOf(navArgument("arg") { type = NavType.StringType; defaultValue = "" })) {
            val kind = runCatching { ReportKind.valueOf(it.arguments?.getString("kind") ?: "") }.getOrDefault(ReportKind.MARKET)
            PdfOptionsScreen(kind, Uri.decode(it.arguments?.getString("arg").orEmpty()).ifBlank { null })
        }
        composable("viewer?path={path}&temp={temp}", arguments = listOf(
            navArgument("path") { type = NavType.StringType; defaultValue = "" },
            navArgument("temp") { type = NavType.StringType; defaultValue = "true" },
        )) {
            PdfViewerScreen(Uri.decode(it.arguments?.getString("path").orEmpty()), it.arguments?.getString("temp") != "false")
        }
    }
}
