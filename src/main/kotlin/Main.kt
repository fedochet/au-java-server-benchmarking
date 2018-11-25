import javafx.application.Application
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.layout.StackPane
import javafx.stage.Stage

class HelloWorld : Application() {
    override fun start(primaryStage: Stage) {
        val btn = Button().apply {
            text = "Say 'Hello World'"
            onAction = EventHandler<ActionEvent> {
                println("Hello world")
            }
        }

        val root = StackPane()
        root.children.add(btn)

        root.prefHeight = 500.0
        root.prefWidth = 600.0

        val scene = Scene(root, 600.0, 500.0)

        primaryStage.title = "Hello World!"
        primaryStage.scene = scene
        primaryStage.show()
    }
}

fun main(args: Array<String>) {
    Application.launch(HelloWorld::class.java, *args)
}

