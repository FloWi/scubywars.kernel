package de.tdng2011.game.kernel

import java.net.{Socket, ServerSocket}
import actors.Actor
import Actor.State._
import java.io.DataInputStream
import de.tdng2011.game.library.util.{StreamUtil, ByteUtil}
import de.tdng2011.game.library.EntityTypes

/*
very very quick and dirty hack, no production code!
*/
object ConnectionHandler extends Runnable {

  var clientActors = List[Actor]()
  val socket = new ServerSocket(1337);

  def event(entityDescriptions : IndexedSeq[EntityDescription]){
    clientActors.foreach(a => if(a.getState != Terminated) a !! entityDescriptions)
  }

  new Thread(this).start


  override def run(){
    while(true) {
      val clientSocket = socket.accept
      val clientThread = new ClientActor(clientSocket).start
      clientActors = clientThread :: clientActors
      clientActors = clientActors.filter(_.getState != Terminated)
      println("ClientActors: " + clientActors.size)
    }
  }
}

class ClientActor(val clientSocket : Socket) extends Actor {
  private var handshakeFinished = false;
  def act = {
    loop {
      react {
        case x : IndexedSeq[EntityDescription] => {
          if(handshakeFinished){
            try {
              if(clientSocket.isConnected){
                clientSocket.getOutputStream.write(ByteUtil.toByteArray(EntityTypes.World))
                x.foreach(b => clientSocket.getOutputStream.write(b.bytes))
              } else {
                exit
              }
            } catch {
              case e => exit
            }
          } else {
            handshake(clientSocket);
          }
        }

        case x : ActorKillMessage => exit

        case _ => {}
      }
    }
  }


  def handshake(clientSocket : Socket) {
    val iStream  = new DataInputStream(clientSocket.getInputStream)
    val buf      = StreamUtil.read(iStream, 8)
    val typeId   = buf.getShort
    val size     = buf.getInt
    val relation = buf.getShort
    if(relation == 0) { // player case, 1 is listener
      handShakePlayer(iStream, size - 2)
    } else if(relation != 1) { // not visualizer
      println("illegal connection from " + clientSocket.getInetAddress + " - closing connection!");
      clientSocket.close
    }
    handshakeFinished=true
  }

  def handShakePlayer(iStream : DataInputStream, size : Int) {
    val name = StreamUtil.read(iStream, size).asCharBuffer.toString
    val player = World !? PlayerAddMessage(name) match {
      case x : Some[Player] => {
        val player = x.get
        new Thread(new ReaderThread(clientSocket,player)).start
        println("started client thread")
        clientSocket.getOutputStream.write(ByteUtil.toByteArray(EntityTypes.Handshake, 0.byteValue, player.publicId))
        ScoreBoard !! PlayerAddToScoreboardMessage(player.publicId, name)
      }
      case x => {
        println("fatal response from player add: " + x)
        clientSocket.getOutputStream.write(ByteUtil.toByteArray(EntityTypes.Handshake, 1.byteValue))
      }
    }
  }
}

class ReaderThread(val clientSocket : Socket, player : Actor) extends Runnable {
   override def run(){
    while(true){
      val msgBuffer = StreamUtil.read(new DataInputStream(clientSocket.getInputStream), 4)
      val turnLeft = msgBuffer.get == 1
      val turnRight = msgBuffer.get == 1
      val thrust = msgBuffer.get == 1
      val fire = msgBuffer.get == 1
      player !! PlayerActionMessage(turnLeft, turnRight, thrust, fire)
      println("server received playerAction: " + turnLeft + " : " + turnRight + " : " + thrust + " : " + fire)
    }
  }
}




