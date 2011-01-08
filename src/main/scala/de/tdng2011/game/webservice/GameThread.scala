package de.tdng2011.game.webservice

import de.tdng2011.game.kernel._
import de.tdng2011.game.visual._
import javax.swing.JFrame
class GameThread extends Runnable {
  override def run() {
    // server start
    // visualizer start
	  val visualizer = CrystalBall
	  


    val m1 = Game.createMonster("test1", "8.8.8.8")
    val m2 = Game.createMonster("fun", "127.0.0.1")
    val m3 = Game.createMonster("felix", "123.123.123.123")

    while (true) {
      Thread.sleep(100)
      Game.monsterAction(m1, true, false, false, true)
      Game.monsterAction(m2, false, true, true, false)
      Game.monsterAction(m3, true, true, true, false)

      Game think(100 / 1000.0)
      
      val world : World = Game.getWorld
      println(world)
      
      visualizer setAllMonsters world
      }
  }
}