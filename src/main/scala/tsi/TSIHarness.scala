package testchipip.tsi

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Parameters, Field}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.prci._
import freechips.rocketchip.amba.axi4._
import testchipip.serdes._
import java.nio.ByteBuffer
import java.nio.file.{Files, Paths}

object TSIHarness {
  def connectRAM(params: SerialTLParams, serdesser: TLSerdesser, port: SerialIO, reset: Reset): SerialRAM = {
    implicit val p: Parameters = serdesser.p

    val ram = LazyModule(new SerialRAM(serdesser, params))

    val module = Module(ram.module)
    module.io.ser <> port

    ram
  }

  def tieoff(serial: Option[SerialIO]) {
    serial.foreach { s =>
      s.in.valid := false.B
      s.in.bits := DontCare
      s.out.ready := true.B
    }
  }

  def tieoff(serial: SerialIO) { tieoff(Some(serial)) }
}

object SerialTLROM {
  def apply(romParams: ManagerROMParams, beatBytes: Int)(implicit p: Parameters): TLROM = {
    lazy val romContents = {
      val romData = romParams.contentFileName.map(n => Files.readAllBytes(Paths.get(n))).getOrElse(
        Array(
          0x1b, 0x05, 0x10, 0x00, // 0010051b     addiw    a0,zero,1
          0x13, 0x15, 0xf5, 0x01, // 01f51513     slli     a0,a0,0x1f (li a0, 0x8000_0000)
          0x73, 0x10, 0x15, 0x34, // 34151073     csrw     mepc,a0
          0x37, 0x25, 0x00, 0x00, // 00002537     lui      a0,0x2
          0x1b, 0x05, 0x05, 0x80, // 8005051b     addiw    a0,a0,-2048
          0x73, 0x20, 0x05, 0x30, // 30052073     csrs     mstatus,a0
          0x73, 0x25, 0x40, 0xf1, // f1402573     csrr     a0,mhartid
          0x73, 0x00, 0x20, 0x30  // 30200073     mret
        ).map(_.toByte)
      )
      val rom = ByteBuffer.wrap(romData)
      rom.array()
    }
    val rom = LazyModule(new TLROM(romParams.address, romParams.size, romContents, true, beatBytes))
    rom
  }
}

class SerialRAM(tl_serdesser: TLSerdesser, params: SerialTLParams)(implicit p: Parameters) extends LazyModule {
  val managerParams = tl_serdesser.module.client_edge.map(_.slave) // the managerParams are the chip-side clientParams
  val clientParams = tl_serdesser.module.manager_edge.map(_.master) // The clientParams are the chip-side managerParams
  val serdesser = LazyModule(new TLSerdesser(
    tl_serdesser.w,
    clientParams,
    managerParams,
    tl_serdesser.bundleParams
  ))

  // If if this serdesser expects a manager, connect tsi2tl
  val tsi2tl = serdesser.managerNode.map { managerNode =>
    val tsi2tl = LazyModule(new TSIToTileLink)
    serdesser.managerNode.get := TLBuffer() := tsi2tl.node
    tsi2tl
  }

  serdesser.clientNode.foreach { clientNode =>
    val beatBytes = 8
    val memParams = params.manager.get.memParams
    val romParams = params.manager.get.romParams
    val cohParams = params.manager.get.cohParams

    val xbar = TLXbar()

    val srams = memParams.map { memParams =>
      AddressSet.misaligned(memParams.address, memParams.size).map { aset =>
        LazyModule(new TLRAM(aset, beatBytes = beatBytes))
      }
    }.flatten
    srams.foreach { s => s.node := TLBuffer() := TLFragmenter(beatBytes, p(CacheBlockBytes)) := xbar }

    val rom = romParams.map { romParams => SerialTLROM(romParams, beatBytes) }
    rom.foreach { r => r.node := TLFragmenter(beatBytes, p(CacheBlockBytes)) := xbar }

    val cohrams = cohParams.map { cohParams =>
      AddressSet.misaligned(cohParams.address, cohParams.size).map { aset =>
        LazyModule(new TLRAM(aset, beatBytes = beatBytes))
      }
    }.flatten
    cohrams.foreach { s => s.node := TLBuffer() := TLFragmenter(beatBytes, p(CacheBlockBytes)) := xbar }

    xbar := TLBroadcast(p(CacheBlockBytes)) := clientNode
  }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val ser = new SerialIO(tl_serdesser.w)
      val tsi = tsi2tl.map(_ => new TSIIO)
      val tsi2tl_state = Output(UInt())
    })

    serdesser.module.io.ser.in <> io.ser.in
    io.ser.out <> serdesser.module.io.ser.out
    io.tsi.foreach(_ <> tsi2tl.get.module.io.tsi)
    io.tsi2tl_state := tsi2tl.map(_.module.io.state).getOrElse(0.U(1.W))

    require(serdesser.module.mergedParams == tl_serdesser.module.mergedParams,
    "Mismatch between chip-side diplomatic params and harness-side diplomatic params:\n" +
      s"Harness-side params: ${serdesser.module.mergedParams}\n" +
      s"Chip-side params: ${tl_serdesser.module.mergedParams}")

  }
}
