package de.tdng2011.game.kernel

import java.net.{Socket, ServerSocket}
import actors.Actor
import Actor.State._
import java.io.DataInputStream
import de.tdng2011.game.library.EntityTypes
import de.tdng2011.game.library.util.{ScubywarsLogger, StreamUtil, ByteUtil}
import collection.immutable.Map

/*
very very quick and dirty hack, no production code!
*/
object ConnectionHandler extends Runnable with ScubywarsLogger {

  var clientActors = List[Actor]()
  val socket = new ServerSocket(1337);

  def event(event : Any){
    clientActors.foreach(a => if(a.getState != Terminated) a !! event)
  }

  new Thread(this).start

  override def run(){
    while(true) {
      val clientSocket = socket.accept
      val clientThread = new ClientActor(clientSocket).start
      clientActors = clientThread :: clientActors
      clientActors = clientActors.filter(_.getState != Terminated)
      logger.debug("Client Actors after filtering: " + clientActors.size)
    }
  }
}

class ClientActor(val clientSocket : Socket) extends Actor with ScubywarsLogger {

  private var player : Player = null

  var relation = 0

  private var handshakeFinished = false;

  def act = {
    loop {
      react {
        case x => sendMessageToClient(x)
      }
    }
  }

  def isNgVisualizer = relation == 3
  def isNgPlayer = relation == 2
  def isNgClient = isNgPlayer || isNgVisualizer

  def sendMessageToClient(message : Any) : Unit =  {

    if(mailboxSize > 15){
      logger.warn("alert, " + this + " client actor has a mailbox size of " + mailboxSize)
    }

    try {
      if(!handshakeFinished) {
        handshake(clientSocket);
        // TODO
        // return
      }

      message match {
        case x : IndexedSeq[EntityDescription] => {
          sendBytesToClient(ByteUtil.toByteArray(EntityTypes.World, x.size))
          x.foreach(b => sendBytesToClient(b.bytes))
        }

        case x : PlayerAddedMessage => {
          sendBytesToClient(ByteUtil.toByteArray(EntityTypes.PlayerJoined, x.publicId, x.name))
        }

        case x : PlayerRemovedMessage => {
          sendBytesToClient(ByteUtil.toByteArray(EntityTypes.PlayerLeft, x.player.publicId))
        }

        case x : ScoreBoardChangedMessage => {
          sendBytesToClient(ByteUtil.toByteArray(EntityTypes.ScoreBoard, x.scoreBoard))
        }

        // NG Messages
        case x : PlayerKilledMessage if isNgClient => {
          sendBytesToClient(ByteUtil.toByteArray(EntityTypes.PlayerKilledEvent, x.victimPublicId, x.shotPublicId, x.killerPublicId, x.shotPosition, x.victimPosition));
        }
        
        case x : PlayerCollisionMessage if isNgClient => {
          sendBytesToClient(ByteUtil.toByteArray(EntityTypes.PlayerCollisionEvent, x.player1PublicId, x.player2PublicId, x.player1Position, x.player2Position));
        }

        case x : ShotCollisionMessage if isNgClient => {
          sendBytesToClient(ByteUtil.toByteArray(EntityTypes.ShotCollisionEvent, x.shot1PublicId, x.shot2PublicId, x.s1Position, x.s2Position));
        }

        case x : PlayerSpawnedMessage if isNgClient => {
          sendBytesToClient(ByteUtil.toByteArray(EntityTypes.PlayerSpawnedEvent, x.publicId, x.position))
        }

        case x : ShotSpawnedMessage if isNgClient => {
           sendBytesToClient(ByteUtil.toByteArray(EntityTypes.ShotSpawnedEvent, x.publicId, x.parentId, x.position))
        }


        case _ => {}
      }

    } catch {
      case e => {
        if(player != null){
          logger.info("player " + player + " left the game (connection closed / error)")
          World !! RemovePlayerFromWorldMessage(player)
        }

        exit
      }
    }
  }

  def sendBytesToClient(bytes : Array[Byte]) {
    if(handshakeFinished) {
      try {
        if(clientSocket.isConnected){
          TimeoutCleanupTread.addSocket(clientSocket)
          clientSocket.getOutputStream.write(bytes)
          TimeoutCleanupTread.removeSocket(clientSocket)
        }
      }
    }
  }

  def handshake(clientSocket : Socket) {
    val iStream  = new DataInputStream(clientSocket.getInputStream)
    val buf      = StreamUtil.read(iStream, 8)
    val typeId   = buf.getShort
    val size     = buf.getInt
    relation = buf.getShort
    logger.debug("Client handshake type: " + typeId + ", size: " + size + ", relation: " + relation)
    if(relation == 0) { // player case, 1 is listener
      handShakePlayer(iStream, size - 2)
    } else if(relation != 1) { // not visualizer
      logger.warn("Illegal connection from " + clientSocket.getInetAddress + " - closing connection!");
      clientSocket.close
    }
    finishHandshake(clientSocket)
  }

  def finishHandshake(clientSocket : Socket) {
    handshakeFinished=true
    for (name <- World.nameMap) {
      sendBytesToClient(ByteUtil.toByteArray(EntityTypes.PlayerName, name._1, name._2))
    }
    sendBytesToClient(ByteUtil.toByteArray(EntityTypes.ScoreBoard, ScoreBoard.scores))
  }

  def handShakePlayer(iStream : DataInputStream, size : Int) {
    val name = StreamUtil.read(iStream, size).asCharBuffer.toString
    World !? AddPlayerMessage(name) match {
      case x : Some[Player] => {
        player = x.get
        new Thread(new ReaderThread(clientSocket,player)).start
        logger.debug("started client thread")

        clientSocket.getOutputStream.write(ByteUtil.toByteArray(EntityTypes.Handshake, 0.byteValue, player.publicId))
      }
      case x => {
        logger.error("Fatal response from player add to world: " + x)
        clientSocket.getOutputStream.write(ByteUtil.toByteArray(EntityTypes.Handshake, 1.byteValue))
      }
    }
  }
}

class ReaderThread(val clientSocket : Socket, player : Actor) extends Runnable with ScubywarsLogger {
   override def run(){
    val iStream = new DataInputStream(clientSocket.getInputStream)
    var reading = true
    while(reading){
      try {
        val buf       = StreamUtil.read(iStream, 6)
        val typeId    = buf.getShort
        val size      = buf.getInt

        val msgBuffer = StreamUtil.read(iStream, size)
        if (typeId == EntityTypes.Action.id) {
          val turnLeft = msgBuffer.get == 1
          val turnRight = msgBuffer.get == 1
          val thrust = msgBuffer.get == 1
          val fire = msgBuffer.get == 1
          player !! PlayerActionMessage(turnLeft, turnRight, thrust, fire)
        } else {
          logger.warn("unknown typeId: " + typeId + " with size: " + size)
        }
      } catch {
        case e => logger.error("Reading from client failed. Stopping reader thread now.", e)
        reading=false
      }
    }
  }
}

object TimeoutCleanupTread extends Runnable with ScubywarsLogger {
  var socketMap = Map[Socket, Long]()

  new Thread(this).start

  override def run(){
    while(true) {
      Thread sleep 100
      socketMap.foreach(s => if(System.currentTimeMillis() - s._2  > 2000) {
        logger.info("write timeout, closing socket")
        s._1.close()
        removeSocket(s._1)
      })
    }
  }

  def addSocket(socket : Socket) {
    socketMap = socketMap + (socket -> System.currentTimeMillis())
  }

  def removeSocket(socket : Socket) {
    socketMap = socketMap - socket
  }
}