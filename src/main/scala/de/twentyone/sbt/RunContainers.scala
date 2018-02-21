package de.twentyone.sbt

import de.twentyone.ProcessUtil.{ReProcess, stringToProcess}
import de.twentyone.sbt.DockerRunPlugin.autoImport.{DockerRunContainer, dockerRunNetwork}
import sbt.Logger

import collection.mutable
import scala.util.{Success, Try}

object ContainerState extends Enumeration {
  type Type = Value
  val Pending = Value
  val Starting = Value
  val Running = Value
  val Up = Value
}

class RunContainers(projectName: String, projectVersion: String, log : Logger, dockerNetwork: String, containers : Seq[(String, DockerRunContainer)]) {
  val containerNames : Map[String, String] = containers.map {
    case (ref, runContainer) => ref -> runContainer.containerName.getOrElse(s"$projectName-$ref-docker-run-$projectVersion")
  }.toMap

  val portMappings : Map[String, Int] = findFreePorts(containers.length).zip(containers).map {
    case (freePort, (ref, _)) => ref -> freePort
  }.toMap

  val started: mutable.Set[String] = mutable.Set.empty

  def start() : Boolean = {
    ensureNetwork()

    var states : Map[String, ContainerState.Type] = Map.empty

    Range(0, 100).exists { _ =>
      states = getStates(states)

      log.info("Docker run: Container states")
      containers.foreach {
        case (ref, _) =>
          log.info(s"Docker run: ${ref.padTo(40, ' ')} is ${states(ref)}")
      }

      if(states.values.forall(_ == ContainerState.Up))
        true
      else {
        containers.foreach {
          case (ref, _) if started.contains(ref) => ()
          case (ref, runContainer) if runContainer.dependsOn.forall(dep => states.get(dep).exists(_ == ContainerState.Running)) =>
            val envParameters: Seq[String] = runContainer.environment
              .flatMap {
                case (name, value) => Seq("-e", s"$name=$value")
              }
            val publishParameters = runContainer.containerPort
              .toSeq
              .flatMap { containerPort =>
                Seq("-p", s"${portMappings(ref)}:$containerPort")
              }

            val exec = Seq("docker", "run", "-d", "--name", containerNames(ref)) ++ envParameters ++ publishParameters ++ runContainer.dockerArgs ++ Seq("--network", dockerNetwork, "--network-alias", ref, runContainer.image)
            log.info(s"""Docker run: Starting `${exec.mkString(" ")}`""")
            ReProcess(exec).!(log)

            started.add(ref)
          case _ => ()
        }

        Thread.sleep(2000)
        false
      }
    }
  }

  def stop() : Unit = {
    containers.foreach {
      case (ref, _) if started.contains(ref) =>
        log.info(s"Docker run: Removing ${containerNames(ref)}")
        s"docker rm -f ${containerNames(ref)}".!(log)
      case _ => ()
    }
    log.info(s"Docker run: Removing network $dockerNetwork")
    s"docker network rm $dockerNetwork".!(log)
  }

  private def ensureNetwork(): Unit = {
    val networkExists = "docker network ls --format \"{{.Name}}\"".lines_!.contains(dockerNetwork)

    if (!networkExists) {
      log.info(s"Docker run: Creating network $dockerNetwork")
      s"docker network create $dockerNetwork".!(log)
    }
  }

  private def getStates(lastState : Map[String, ContainerState.Type]): Map[String, ContainerState.Type] =
    containers.map {
      case (ref, runContainer) if started.contains(ref) && !lastState.get(ref).exists(_ == ContainerState.Up)=>
        ref -> (getState(containerNames(ref), runContainer.waitHealthy) match {
          case ContainerState.Running if lastState.get(ref).exists(_ == ContainerState.Running) => ContainerState.Up
          case state => state
        })
      case (ref, _) => ref -> lastState.getOrElse(ref, ContainerState.Pending)
    }.toMap

  private def getState(containerName: String, requireHealthy: Boolean) : ContainerState.Type = {
    if (requireHealthy) {
      Try { ("docker inspect -f \"{{.State.Health.Status}}\" " + containerName).!! } match {
        case Success(output) if output.contains("healthy") => ContainerState.Running
        case Success(_) => ContainerState.Starting
        case _ => ContainerState.Pending
      }
    } else {
      Try { ("docker inspect -f \"{{.State.Status}}\" " + containerName).!! } match {
        case Success(output) if output.contains("running") => ContainerState.Running
        case Success(_) => ContainerState.Starting
        case _ => ContainerState.Pending
      }
    }
  }

  def findFreePorts(count: Int) : Seq[Int] = {
    val sockets = Range(0, count).map { _ =>
      var socket = new java.net.ServerSocket(0)
      socket.setReuseAddress(true)
      socket
    }
    val ports = sockets.map(_.getLocalPort)
    sockets.foreach(_.close())
    ports
  }
}
