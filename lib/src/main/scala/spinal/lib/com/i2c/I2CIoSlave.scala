/*                                                                           *\
**        _____ ____  _____   _____    __                                    **
**       / ___// __ \/  _/ | / /   |  / /   HDL Lib                          **
**       \__ \/ /_/ // //  |/ / /| | / /    (c) Dolu, All rights reserved    **
**      ___/ / ____// // /|  / ___ |/ /___                                   **
**     /____/_/   /___/_/ |_/_/  |_/_____/  MIT Licence                      **
**                                                                           **
** Permission is hereby granted, free of charge, to any person obtaining a   **
** copy of this software and associated documentation files (the "Software"),**
** to deal in the Software without restriction, including without limitation **
** the rights to use, copy, modify, merge, publish, distribute, sublicense,  **
** and/or sell copies of the Software, and to permit persons to whom the     **
** Software is furnished to do so, subject to the following conditions:      **
**                                                                           **
** The above copyright notice and this permission notice shall be included   **
** in all copies or substantial portions of the Software.                    **
**                                                                           **
** THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS   **
** OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF                **
** MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.    **
** IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY      **
** CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT **
** OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR  **
** THE USE OR OTHER DEALINGS IN THE SOFTWARE.                                **
\*                                                                           */
package spinal.lib.com.i2c

import spinal.core._
import spinal.lib._


/**
  * Generics for the I2C Slave
  *
  * @param samplingSize              : deepth sampling
  * @param clockDividerSamplingWidth : Width of the clock divider
  */
case class I2CIoSlaveGenerics(samplingSize             : Int = 3,
                              clockDividerSamplingWidth: BitCount = 10 bits ){}


/**
  * Run-time configuration for the I2CSlave
  */
case class I2CIoSlaveConfig(g: I2CIoSlaveGenerics) extends Bundle {

  val clockDividerSampling = UInt(g.clockDividerSamplingWidth)

  def setFrequencySampling(frequencySampling : HertzNumber, clkFrequency : HertzNumber = ClockDomain.current.frequency.getValue): Unit = {
    clockDividerSampling := (clkFrequency / frequencySampling).toInt
  }
}


/**
  * I2C Slave IO Layer :
  *
  *  This component manages the low level of the I2C protocol. (START, STOP, Send & Receive bit data)
  *
  *          ________                       ________
  *         |        |<------- I2C ------->|        |
  *         | Master |                     |  Slave |
  *         |________|      RSP Stream --->|________|---> CMD Flow
  *
  * Write sequence :
  *
  *   RSP    :           DATA    DATA   DATA    DATA    DATA
  *   Master :   | START | DATA  |       | DATA  | DATA  | STOP |
  *   Slave  :   |       |       | DATA  |       |       |      |
  *   CMD    :        START    DATA    DATA    DATA    DATA    STOP
  *
  *
  * Restart sequence :
  *
  *   RSP    :           DATA   DATA   DATA    DATA   DATA
  *   Master :   | START |      | DATA | START | DATA  | STOP |
  *   Slave  :   |       | DATA |      |       |       |      |
  *   CMD    :       START   DATA    DATA    START   DATA   STOP
  */
class I2CIoSlave(g : I2CIoSlaveGenerics) extends Component{

  import spinal.lib.com.i2c.{I2CIoCmdMode => CmdMode}

  /**
    * Interface of the I2C Hal slave
    */
  val io = new Bundle{
    val i2c    = master( I2C() )
    val config = in( I2CIoSlaveConfig(g) )
    val cmd    = master Flow  ( I2CIoCmd() )
    val rsp    = slave  Stream( I2CIoRsp() )
  }

  /**
    * Filter SDA and SCL input
    */
  val filter = new I2CIoFilter(i2c_sda           = io.i2c.sda.read,
                               i2c_scl           = io.i2c.scl.read,
                               clockDivider      = io.config.clockDividerSampling,
                               samplingSize      = g.samplingSize,
                               clockDividerWidth = g.clockDividerSamplingWidth)

  /**
    * Detect the rising and falling edge of the scl signal
    */
  val sclEdge = new I2CEdgeDetector(filter.scl)
  val sdaEdge = new I2CEdgeDetector(filter.sda)


  /**
    * Detect the start/restart and the stop sequences
    */
  val detector = new Area{
    val start = filter.scl && sdaEdge.falling
    val stop  = filter.scl && sdaEdge.rising
  }


  /**
    * Slave controller
    */
  val ctrlSlave = new Area{
    val inFrame, inFrameData = Reg(Bool) init(False)
    val sdaWrite, sclWrite = True

    // default value
    io.cmd.valid := False
    io.cmd.data  := RegNextWhen(filter.sda, sclEdge.rising)
    io.cmd.mode  := CmdMode.DATA
    io.rsp.ready := False

    // Detect a start condition
    when(detector.start){
      io.cmd.valid  := True
      io.cmd.mode   := CmdMode.START
      inFrame := True
      inFrameData := False //Allow restart
    }

    // After detecting the start wait the falling edge of
    //  the clock before starting to read/send bit data
    when(inFrame && sclEdge.falling){
      inFrameData := True
    }

    when(detector.stop){
      io.cmd.valid  := True
      io.cmd.mode   := CmdMode.STOP
      inFrameData := False
      inFrame := False
    }

    // Send & Receive bit data
    when(inFrameData) {
      // Freeze the bus if no response received
      sclWrite clearWhen(!io.rsp.valid)

      // Manage enabled io.rsp
      when(io.rsp.valid && io.rsp.enable){
        sclWrite clearWhen(filter.sda =/= io.rsp.data)
        sdaWrite := io.rsp.data
      }

      io.cmd.valid := sclEdge.falling
      io.rsp.ready := sclEdge.falling
    }
  }

  /*
   * Drive SCL & SDA signals
   */
  io.i2c.scl.write := RegNext(ctrlSlave.sclWrite)
  io.i2c.sda.write := RegNext(ctrlSlave.sdaWrite)
}