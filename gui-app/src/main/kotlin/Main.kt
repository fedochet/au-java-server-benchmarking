import config.ClientConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.http.isSuccess
import javafx.application.Application
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.fxml.Initializable
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.SplitPane
import javafx.scene.control.TextField
import javafx.stage.Stage
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URL
import java.util.*

private const val CLIENT_JAR_NAME = "client-app.jar"

class MainScreenController : Initializable {
    @FXML
    lateinit var mainScreen: SplitPane

    @FXML
    lateinit var serverAddress: TextField

    @FXML
    lateinit var serverPort: TextField

    @FXML
    lateinit var testServerButton: Button

    val client = HttpClient(Apache) {
        install(JsonFeature) {
            serializer = GsonSerializer {
                serializeNulls()
                disableHtmlEscaping()
            }
        }
    }

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        updateTestButton()

        serverAddress.textProperty().addListener { _, _, _ ->
            updateTestButton()
        }

        serverPort.textProperty().addListener { _, _, _ ->
            updateTestButton()
        }
    }

    fun onTestButtonPressed() {
        val address = serverAddress.text
        val port = serverPort.text.toInt()

        val alert = try {
            val status = runBlocking { client.call("http://$address:$port/status").response.status }
            if (status.isSuccess()) {
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

    private fun updateTestButton() {
        testServerButton.isDisable =
            serverPort.text?.toIntOrNull() == null
            || serverAddress.text.isNullOrEmpty()
    }
}

class MainApp : Application() {

    private lateinit var primaryStage: Stage
    private lateinit var rootLayout: SplitPane

    override fun start(primaryStage: Stage) {
        this.primaryStage = primaryStage
        this.primaryStage.title = "Server architecture benchmarking app"

        initRootLayout()
    }

    /**
     * Инициализирует корневой макет.
     */
    private fun initRootLayout() {
        val loader = FXMLLoader()
        loader.location = "view/MainScreen.fxml".asResource()
        rootLayout = loader.load<SplitPane>()

        // Отображаем сцену, содержащую корневой макет.
        val scene = Scene(rootLayout)
        primaryStage.scene = scene
        primaryStage.show()
    }
}


fun main(args: Array<String>) {
    Application.launch(MainApp::class.java, *args)
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