package gui

import config.ClientConfig
import config.ServerConfig
import config.ServerType
import config.SingleRunConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javafx.application.Application
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.fxml.Initializable
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.chart.LineChart
import javafx.scene.chart.XYChart
import javafx.scene.control.*
import javafx.stage.Stage
import kotlinx.coroutines.runBlocking
import stats.SessionRawStats
import stats.jobDuration
import stats.requestDuration
import java.io.File
import java.net.InetAddress
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

private const val SERVER_PORT = 8090

private const val CLIENT_JAR_NAME = "client-app.jar"
private val CLIENT_JAR = extractClientJar()

enum class VaryingParam(val description: String) {
    N("N (number of elements)"),
    M("M (number of clients)"),
    DELTA("DELTA (pause duration)");

    override fun toString(): String = description
}

data class PerformanceStats(
    val varyingParamValue: Long,
    val serverRequestDuration: Double,
    val jobDuration: Double,
    val clientRequestDuration: Double
)

class ServerClient(private val address: String, private val port: Int) {
    private val client = HttpClient(Apache) {
        // to make methods throw exceptions if response is not successful
        expectSuccess = true
        install(JsonFeature) {
            serializer = GsonSerializer {
                serializeNulls()
                disableHtmlEscaping()
            }
        }
    }

    fun status(): Boolean {
        val status = runBlocking { client.call("http://$address:$port/status").response.status }
        return status.isSuccess()
    }

    fun startServer(serverType: ServerType, serverPort: Int) {
        runBlocking {
            client.call {
                url {
                    host = address
                    port = this@ServerClient.port
                    encodedPath = "/server/start"
                }
                method = HttpMethod.Post
                body = ServerConfig(serverType, serverPort)
                contentType(ContentType.Application.Json)
            }.response
        }
    }

    fun stopServer() {
        runBlocking {
            client.call {
                url {
                    host = address
                    port = this@ServerClient.port
                    encodedPath = "/server/stop"
                }
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
            }
        }
    }

    fun getStatistics(): SessionRawStats {
        return runBlocking {
            client.get<SessionRawStats>(
                host = address,
                port = port,
                path = "/stats/get"
            )
        }
    }

    fun clearStatistics() {
        runBlocking {
            client.call {
                url {
                    host = address
                    port = this@ServerClient.port
                    encodedPath = "/stats/clear"
                }
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
            }
        }
    }
}

class ChartScreenController : Initializable {
    @FXML
    lateinit var chartOne: LineChart<Number, Number>
    @FXML
    lateinit var chartTwo: LineChart<Number, Number>
    @FXML
    lateinit var chartThree: LineChart<Number, Number>

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        chartOne.title = "Server Request duration"
        chartTwo.title = "Job Duration"
        chartThree.title = "clientRequestDuration"
    }

    fun showData(varyingParam: VaryingParam, serverType: ServerType, measurements: List<PerformanceStats>) {
        chartOne.xAxis.label = varyingParam.description
        chartTwo.xAxis.label = varyingParam.description
        chartThree.xAxis.label = varyingParam.description

        val serverRequestDurationSeries = XYChart.Series<Number, Number>().apply { name = serverType.name }
        val jobDurationSeries = XYChart.Series<Number, Number>().apply { name = serverType.name }
        val clientRequestDurationSeries = XYChart.Series<Number, Number>().apply { name = serverType.name }

        serverRequestDurationSeries.data.addAll(measurements.map {
            XYChart.Data<Number, Number>(it.varyingParamValue, it.serverRequestDuration)
        })

        jobDurationSeries.data.addAll(measurements.map {
            XYChart.Data<Number, Number>(it.varyingParamValue, it.jobDuration)
        })

        clientRequestDurationSeries.data.addAll(measurements.map {
            XYChart.Data<Number, Number>(it.varyingParamValue, it.clientRequestDuration)
        })

        chartOne.data.add(serverRequestDurationSeries)
        chartTwo.data.add(jobDurationSeries)
        chartThree.data.add(clientRequestDurationSeries)
    }
}

class MainScreenController : Initializable {
    @FXML
    lateinit var mainScreen: SplitPane
    @FXML
    lateinit var serverAddress: TextField
    @FXML
    lateinit var serverPort: TextField
    @FXML
    lateinit var testServerButton: Button
    @FXML
    lateinit var serverTypeChoiceBox: ChoiceBox<ServerType>
    @FXML
    lateinit var varyingParamChoiceBox: ChoiceBox<VaryingParam>
    @FXML
    lateinit var varyingParamLabel: Label
    @FXML
    lateinit var varyingParamFromField: TextField
    @FXML
    lateinit var varyingParamToField: TextField
    @FXML
    lateinit var varyingParamStepField: TextField
    @FXML
    lateinit var firstConstantParamLabel: Label
    @FXML
    lateinit var firstConstantParamField: TextField
    @FXML
    lateinit var secondConstantParamLabel: Label
    @FXML
    lateinit var secondConstantParamField: TextField
    @FXML
    lateinit var numberOfRequestsField: TextField
    @FXML
    lateinit var startTestingButton: Button

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        serverTypeChoiceBox.items.addAll(ServerType.values())
        serverTypeChoiceBox.selectionModel.select(0)

        varyingParamChoiceBox.items.addAll(VaryingParam.values())
        varyingParamChoiceBox.selectionModel.selectedItemProperty().onChange(this::updateVaryingParamsLabels)
        varyingParamChoiceBox.selectionModel.select(0)

        updateTestButton()
        serverAddress.textProperty().onChange(this::updateTestButton)
        serverPort.textProperty().onChange(this::updateTestButton)

        updateStartTestingButton()
        arrayOf(
            numberOfRequestsField,
            varyingParamFromField,
            varyingParamToField,
            varyingParamStepField,
            firstConstantParamField,
            secondConstantParamField
        ).forEach { it.textProperty().onChange(this::updateStartTestingButton) }
    }

    private fun updateVaryingParamsLabels(value: VaryingParam) {
        varyingParamLabel.text = value.description
        val (first, second) = VaryingParam.values().toMutableList().apply { remove(value) }
        firstConstantParamLabel.text = first.description
        secondConstantParamLabel.text = second.description
    }

    fun onTestButtonPressed() {
        val serverClient = getServerClient()

        val alert = try {
            if (serverClient.status()) {
                Alert(Alert.AlertType.INFORMATION, "Connection is successful")
            } else {
                Alert(Alert.AlertType.ERROR, "Cannot connect to server")
            }
        } catch (e: Exception) {
            Alert(Alert.AlertType.ERROR, e.message)
        }

        alert.initOwner(mainScreen.scene.window)
        alert.show()
    }

    private fun getServerClient(): ServerClient {
        val address = serverAddress.text
        val port = serverPort.text.toInt()

        return ServerClient(address, port)
    }

    fun onStartTestingButtonPressed() {
        val executor = Executors.newCachedThreadPool()

        try {
            val serverClient = getServerClient()
            if (!runCatching { serverClient.status() }.getOrElse { false }) {
                Alert(Alert.AlertType.ERROR, "Cannot connect to server").apply {
                    initOwner(mainScreen.scene.window)
                    showAndWait()
                }

                return
            }

            // just in case
            serverClient.stopServer()

            val serverType = serverTypeChoiceBox.selectionModel.selectedItem
            serverClient.startServer(serverType, SERVER_PORT)

            val varyingParam = varyingParamChoiceBox.selectionModel.selectedItem

            val configs = generateConfigurations(varyingParam)
            if (configs.isEmpty()) {
                Alert(Alert.AlertType.ERROR, "Invalid configuration").apply {
                    initOwner(mainScreen.scene.window)
                    showAndWait()
                }

                return
            }

            val results = mutableListOf<Pair<SingleRunConfig, SessionRawStats>>()

            for (config in configs) {
                serverClient.clearStatistics()

                val clients = List(config.numberOfClients) { runClient(CLIENT_JAR, config.clientConfig) }

                val clientFutures = clients.mapIndexed { idx, process ->
                    CompletableFuture.runAsync(Runnable {
                        val status = process.waitFor()
                        if (status != 0) {
                            println("Client #$idx exited unsuccessfully")
                            runCatching {
                                process.errorStream
                                    .bufferedReader()
                                    .lineSequence()
                                    .forEach(::println)
                            }
                        }
                    }, executor)
                }.toTypedArray()

                CompletableFuture.anyOf(*clientFutures).get()

                clients.forEach {
                    it.destroy()
                }

                CompletableFuture.allOf(*clientFutures).get()

                println("Finished for config $config")
                results += Pair(config, serverClient.getStatistics())
            }

            val measurements = results.mapNotNull { (config, stats) ->
                computeMeasurements(varyingParam, config, stats)?.let { config to it }
            }

            saveAsCsv(serverType, varyingParam, measurements)

            val loader = FXMLLoader("view/ChartScreen.fxml".asResource())
            val root = loader.load<Parent>()
            val controller = loader.getController<ChartScreenController>()

            val stage = Stage().apply {
                title = "Charts with result"
                scene = Scene(root, 450.0, 450.0)
                initOwner(mainScreen.scene.window)
            }

            controller.showData(varyingParam, serverType, measurements.map { it.second })
            stage.showAndWait()

        } catch (e: Exception) {
            e.printStackTrace()
            Alert(Alert.AlertType.ERROR, "Some error happened during execution: ${e.message}").apply {
                initOwner(mainScreen.scene.window)
                showAndWait()
            }
        } finally {
            executor.shutdown()
        }
    }

    private fun computeMeasurements(varyingParam: VaryingParam, config: SingleRunConfig, stats: SessionRawStats): PerformanceStats? {
        val lastConnect = stats.clients.maxBy { it.connectionStart }?.connectionStart ?: return null
        val firstDisconnect = stats.clients.minBy { it.connectionEnd }?.connectionEnd ?: return null

        if (firstDisconnect - lastConnect <= 0) {
            println("Skipping config $config, no valid time")
            return null
        }

        val averageClientRequestTime = stats.clients.map { client ->
            val validRequestsCount = client.requests.asSequence()
                .filter { it.requestStart >= lastConnect }
                .filter { it.requestEnd <= firstDisconnect }
                .count()

            val firstValidRequestStart = client.requests.firstOrNull { it.requestStart >= lastConnect }?.requestStart
            val lastValidRequestEnd = client.requests.lastOrNull { it.requestEnd <= firstDisconnect }?.requestEnd

            if (firstValidRequestStart == null || lastValidRequestEnd == null) {
                println("Some client does not have requests in shared time")
                return null
            }

            val validTime = lastValidRequestEnd - firstValidRequestStart
            validTime / validRequestsCount.toDouble() - config.clientConfig.pauseDuration
        }.average()

        val validRequests = stats.clients.asSequence()
            .flatMap { it.requests.asSequence() }
            .filter { it.requestStart >= lastConnect }
            .filter { it.requestEnd <= firstDisconnect }

        val avgRequestDuration = validRequests.map { it.requestDuration }.average()
        val jobDuration = validRequests.map { it.jobDuration }.average()

        val varyingParamValue = when (varyingParam) {
            VaryingParam.N -> config.clientConfig.numberOfElements.toLong()
            VaryingParam.M -> config.numberOfClients.toLong()
            VaryingParam.DELTA -> config.clientConfig.pauseDuration
        }

        return PerformanceStats(varyingParamValue, avgRequestDuration, jobDuration, averageClientRequestTime)
    }

    private fun updateStartTestingButton() {
        startTestingButton.isDisable =
            arrayOf(
                numberOfRequestsField,
                varyingParamFromField,
                varyingParamToField,
                varyingParamStepField,
                firstConstantParamField,
                secondConstantParamField
            ).any { it.text.toIntOrNull() == null }
    }

    private fun updateTestButton() {
        testServerButton.isDisable =
            serverPort.text?.toIntOrNull() == null
            || serverAddress.text.isNullOrEmpty()
    }

    private fun generateConfigurations(varyingParam: VaryingParam): List<SingleRunConfig> {
        val address = InetAddress.getByName(serverAddress.text)

        val from = varyingParamFromField.textAsInt
        val to = varyingParamToField.textAsInt
        val step = varyingParamStepField.textAsInt

        val varyingParamValues = from..to step step

        val numberOfRequests = numberOfRequestsField.textAsInt

        val firstConst = firstConstantParamField.textAsInt
        val secondConst = secondConstantParamField.textAsInt

        return varyingParamValues.map { varyingVal ->
            val (numberOfElements, numberOfClients, pauseDuration) = when (varyingParam) {
                VaryingParam.N -> arrayOf(varyingVal, firstConst, secondConst)
                VaryingParam.M -> arrayOf(firstConst, varyingVal, secondConst)
                VaryingParam.DELTA -> arrayOf(firstConst, secondConst, varyingVal)
            }

            SingleRunConfig(
                numberOfClients,
                ClientConfig(
                    address,
                    SERVER_PORT,
                    numberOfElements,
                    numberOfRequests,
                    pauseDuration.toLong()
                )
            )
        }
    }
}

private val dateFormat =
    DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

private fun saveAsCsv(serverType: ServerType, varyingParam: VaryingParam, measurements: List<Pair<SingleRunConfig, PerformanceStats>>) {
    if (measurements.isEmpty()) return

    val currentTime = dateFormat.format(Instant.now())

    val jobDurationFile = File("JobDuration $currentTime.csv").apply { createNewFile() }
    val serverRequestFile = File("Server request duration $currentTime.csv").apply { createNewFile() }
    val clientRequestFile = File("Client request duration $currentTime.csv").apply { createNewFile() }

    val (firstConst, secondConst) = VaryingParam.values().toMutableList().apply { remove(varyingParam) }
    val constParamsTitle = "Server type, X, ${firstConst.description}, ${secondConst.description}"

    val (firstConfig, _) = measurements.first()
    val (firstVal, secondVal) = when (varyingParam) {
        VaryingParam.N -> Pair(firstConfig.numberOfClients, firstConfig.clientConfig.pauseDuration)
        VaryingParam.M -> Pair(firstConfig.clientConfig.numberOfElements, firstConfig.clientConfig.pauseDuration)
        VaryingParam.DELTA -> Pair(firstConfig.clientConfig.numberOfElements, firstConfig.numberOfClients)
    }

    val constParamsValues = "$serverType, ${firstConfig.clientConfig.numberOfRequests}, $firstVal, $secondVal"

    jobDurationFile.printWriter().use { writer ->
        writer.println("${varyingParam.description}, Job duration")
        for ((_, m) in measurements) {
            writer.println("${m.varyingParamValue}, ${m.jobDuration}")
        }
        writer.println(constParamsTitle)
        writer.println(constParamsValues)
    }

    serverRequestFile.printWriter().use { writer ->
        writer.println("${varyingParam.description}, Request duration for server")
        for ((_, m) in measurements) {
            writer.println("${m.varyingParamValue}, ${m.serverRequestDuration}")
        }
        writer.println(constParamsTitle)
        writer.println(constParamsValues)
    }

    clientRequestFile.printWriter().use { writer ->
        writer.println("${varyingParam.description}, Request duration for client")
        for ((_, m) in measurements) {
            writer.println("${m.varyingParamValue}, ${m.clientRequestDuration}")
        }
        writer.println(constParamsTitle)
        writer.println(constParamsValues)
    }
}

class MainScreen : Application() {

    private lateinit var primaryStage: Stage
    private lateinit var rootLayout: SplitPane

    override fun start(primaryStage: Stage) {
        this.primaryStage = primaryStage
        this.primaryStage.title = "Server architecture benchmarking app"

        initRootLayout()
    }

    private fun initRootLayout() {
        val loader = FXMLLoader()
        loader.location = "view/MainScreen.fxml".asResource()
        rootLayout = loader.load<SplitPane>()

        val scene = Scene(rootLayout)
        primaryStage.scene = scene
        primaryStage.show()
    }
}


fun main(args: Array<String>) {
    Application.launch(MainScreen::class.java, *args)
}

private fun runClient(jarFile: File, clientConfig: ClientConfig): Process {
    val builder = ProcessBuilder(
        listOf("java", "-jar", jarFile.absolutePath) + clientConfig.toCommandLineArgs()
    )

    return builder.start()
}

private fun ClientConfig.toCommandLineArgs(): List<String> = listOf(
    "--address", serverAddress.hostAddress,
    "--port", serverPort.toString(),
    "--N", numberOfElements.toString(),
    "--X", numberOfRequests.toString(),
    "--delta", pauseDuration.toString()
)

private fun extractClientJar(): File {
    val jar = CLIENT_JAR_NAME.asResource()

    return File.createTempFile("client-app", ".jar").apply {
        writeBytes(jar.readBytes())
    }
}

private fun String.asResource(): URL =
    object {}.javaClass.classLoader.getResource(this)
        ?: throw IllegalArgumentException("No such resource: $this")