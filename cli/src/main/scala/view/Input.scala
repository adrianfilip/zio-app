package view

import org.jline.keymap.{BindingReader, KeyMap}
import org.jline.terminal.Terminal.{Signal, SignalHandler}
import org.jline.terminal.{Attributes, Terminal, TerminalBuilder}
import org.jline.utils.InfoCmp.Capability
import zio._
import zio.blocking.{Blocking, effectBlocking, effectBlockingInterrupt}
import zio.stream.ZStream

object Input {
  lazy val ec = new EscapeCodes(System.out)

  private lazy val terminal: org.jline.terminal.Terminal =
    TerminalBuilder
      .builder()
      .jna(true)
      .system(true)
      .nativeSignals(true)
      .signalHandler(Terminal.SignalHandler.SIG_IGN)
      .build();

  def rawModeManaged(fullscreen: Boolean = true): ZManaged[Blocking, Nothing, Attributes] = ZManaged.make {
    for {
      originalAttrs <- effectBlocking(terminal.enterRawMode()).orDie
      _ <- effectBlocking {
        if (fullscreen) {
          terminal.puts(Capability.enter_ca_mode)
          terminal.puts(Capability.keypad_xmit)
          terminal.puts(Capability.clear_screen)
          ec.alternateBuffer()
          ec.clear()
        }
        ec.hideCursor()
      }.orDie
    } yield originalAttrs
  } { originalAttrs =>
    (for {
      _ <- effectBlocking {
        terminal.setAttributes(originalAttrs)
        terminal.puts(Capability.exit_ca_mode)
        terminal.puts(Capability.keypad_local)
        terminal.puts(Capability.cursor_visible)
        ec.normalBuffer()
        ec.showCursor()
      }
    } yield ()).orDie
  }

  lazy val terminalSizeStream: ZStream[Blocking, Nothing, (Int, Int)] =
    ZStream.fromEffect(blocking.blocking(UIO(terminalSize))) ++
      ZStream.effectAsync { register => addResizeHandler(size => register(UIO(Chunk(size)))) }

  private def addResizeHandler(f: ((Int, Int)) => Unit): SignalHandler =
    terminal.handle(Signal.WINCH, _ => { f(terminalSize) })

  def terminalSize: (Int, Int) = {
    val size   = Input.terminal.getSize
    val width  = size.getColumns
    val height = size.getRows
    (width, height)
  }

  def withRawMode[R, E, A](zio: ZIO[R, E, A]): ZIO[R with Blocking, E, A] =
    rawModeManaged(true).use_(zio)

  lazy val keyMap: KeyMap[KeyEvent] = {
    val keyMap = new KeyMap[KeyEvent]

    for (i <- 32 to 256) {
      val str = Character.toString(i.toChar)
      keyMap.bind(KeyEvent.Character(i.toChar), str);
    }

    keyMap.bind(KeyEvent.Exit, KeyMap.key(terminal, Capability.key_exit), Character.toString(3.toChar))
    keyMap.bind(KeyEvent.Up, KeyMap.key(terminal, Capability.key_up), "[A")
    keyMap.bind(KeyEvent.Left, KeyMap.key(terminal, Capability.key_down), "[D")
    keyMap.bind(KeyEvent.Down, KeyMap.key(terminal, Capability.key_left), "[B")
    keyMap.bind(KeyEvent.Right, KeyMap.key(terminal, Capability.key_right), "[C")
    keyMap.bind(KeyEvent.Delete, KeyMap.key(terminal, Capability.key_backspace), KeyMap.del())
    keyMap.bind(KeyEvent.Enter, KeyMap.key(terminal, Capability.carriage_return), "\n")

    keyMap
  }

  private lazy val bindingReader = new BindingReader(terminal.reader())

  private val readBinding: RIO[Blocking, KeyEvent] =
    effectBlockingInterrupt(bindingReader.readBinding(keyMap))

  val keyEventStream: ZStream[Blocking, Throwable, KeyEvent] =
    ZStream.repeatEffect(readBinding) merge
      ZStream.effectAsync[Any, Nothing, KeyEvent](register =>
        terminal.handle(
          Signal.INT,
          _ => register(UIO(Chunk(KeyEvent.Exit)))
        )
      )
}
