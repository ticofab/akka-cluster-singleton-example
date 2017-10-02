import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props, Stash, Terminated}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{ClusterDomainEvent, MemberUp}
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.routing.RoundRobinPool
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object AkkaClusterSingletonExampleApp extends App {
  implicit val as = ActorSystem("akka-cluster-singleton-example")
  implicit val am = ActorMaterializer()
  implicit val ec = as.dispatcher

  val config = ConfigFactory.load()
  val port = config.getInt("akka.remote.netty.tcp.port")
  val role = config.getStringList("akka.cluster.roles")

  println("application starting on node with port " + port + " and roles " + role)

  val master = {
    val singletonProps = ClusterSingletonManager.props(
      singletonProps = Props[Master],
      terminationMessage = PoisonPill,
      settings = ClusterSingletonManagerSettings(as))
    as.actorOf(singletonProps, "master-singleton")
  }

  val proxy = {
    val proxyProps = ClusterSingletonProxy.props(
      singletonManagerPath = "/user/master-singleton",
      settings = ClusterSingletonProxySettings(as))
    as.actorOf(proxyProps, name = "proxy")
  }

  var counter = 0
  if (port == 2551) {
    as.scheduler.schedule(0.seconds, 1.seconds, () => {
      counter += 1
      proxy ! s"msg number $counter"
    })
  }

}

class Master extends Actor {
  val config = ConfigFactory.load()
  val port = config.getInt("akka.remote.netty.tcp.port")
  val role = config.getStringList("akka.cluster.roles")

  println(self.path.name + " starting on node with port " + port + " and roles " + role)
  Cluster(context.system).subscribe(self, classOf[ClusterDomainEvent])

  var workerCounter = 0

  def createWorkerRouter: ActorRef = {
    context.actorOf(
      ClusterRouterPool(
        RoundRobinPool(workerCounter),
        ClusterRouterPoolSettings(
          totalInstances = 1000,
          maxInstancesPerNode = 1,
          allowLocalRoutees = false
        )
      ).props(Props[Worker]),
      name = "worker-router")
  }

  override def receive = empty

  def empty: Receive = {
    case s: String => println(s"${self.path.name} received $s, but no worker is there")
    case MemberUp(m) =>
      workerCounter += 1
      println(s"${self.path.name}, adding first worker to router")
      val router = createWorkerRouter
      context watch router
      context become ready(router)
  }

  def ready(router: ActorRef): Receive = {

    case s: String =>
      println(s"${self.path.name}, forwarding $s")
      router ! s

    case MemberUp(m) =>
      workerCounter += 1
      println(s"${self.path.name}, adding worker to router, now $workerCounter workers")

      // dismiss current router
      router ! PoisonPill

    case Terminated(corpse) =>
      println(s"${self.path.name}, actor ${corpse.path.name} is terminated, creating another one")
      val router = createWorkerRouter
      context watch router
      context become ready(router)
  }

}

class Worker extends Actor {
  override def receive = {
    case s: String => println(s"worker ${self.path.name} received $s")
  }
}