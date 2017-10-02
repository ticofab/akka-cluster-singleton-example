import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

object AkkaClusterSingletonExampleApp extends App {
  implicit val system = ActorSystem("akka-cluster-singleton-example")
  implicit val materializer = ActorMaterializer()

  val config = ConfigFactory.load()
  val port = config.getInt("akka.remote.netty.tcp.port")
  val role = config.getStringList("akka.cluster.roles")

  println("application starting on node with port " + port + " and roles " + role)

  val master = {
    val singletonProps = ClusterSingletonManager.props(
      singletonProps = Props[Master],
      terminationMessage = PoisonPill,
      settings = ClusterSingletonManagerSettings(system))
    system.actorOf(singletonProps, "master-singleton")
  }

  val proxy = {
    val proxyProps = ClusterSingletonProxy.props(
      singletonManagerPath = "/user/master-singleton",
      settings = ClusterSingletonProxySettings(system))
    system.actorOf(proxyProps, name = "proxy")
  }

  proxy ! s"hello from port $port"

}

class Master extends Actor {
  val config = ConfigFactory.load()
  val port = config.getInt("akka.remote.netty.tcp.port")
  val role = config.getStringList("akka.cluster.roles")

  println(self.path.name + " starting on node with port " + port + " and roles " + role)

  override def receive = {
    case s: String => println(self.path.name + " received " + s)
  }

}