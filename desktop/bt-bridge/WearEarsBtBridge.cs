using System;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace WearEarsBtBridge
{
    internal static class Program
    {
        private const ushort AF_BTH = 32;
        private const int SOCK_STREAM = 1;
        private const int BTHPROTO_RFCOMM = 3;
        private const int SD_BOTH = 2;
        private const uint WM_QUIT = 0x0012;
        private const uint WM_HOTKEY = 0x0312;
        private const uint WM_USER = 0x0400;
        private const uint HOTKEY_MODIFIERS = 0x0002 | 0x0001 | 0x4000; // Ctrl + Alt, no repeat
        private static readonly uint[] HotkeyKeys = { 0x57, 0x4E, 0x41, 0x54, 0x4F };
        private static readonly string[] HotkeyActions = {
            "TOGGLE_PANEL", "CYCLE_NOISE", "NOISE_ANC", "NOISE_TRANS", "NOISE_OFF"
        };
        private static readonly IntPtr INVALID_SOCKET = new IntPtr(-1);

        [StructLayout(LayoutKind.Sequential)]
        private struct MSG
        {
            public IntPtr hwnd;
            public uint message;
            public UIntPtr wParam;
            public IntPtr lParam;
            public uint time;
            public int x;
            public int y;
            public uint lPrivate;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct SYSTEMTIME
        {
            public ushort wYear;
            public ushort wMonth;
            public ushort wDayOfWeek;
            public ushort wDay;
            public ushort wHour;
            public ushort wMinute;
            public ushort wSecond;
            public ushort wMilliseconds;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct BLUETOOTH_DEVICE_SEARCH_PARAMS
        {
            public uint dwSize;
            [MarshalAs(UnmanagedType.Bool)] public bool fReturnAuthenticated;
            [MarshalAs(UnmanagedType.Bool)] public bool fReturnRemembered;
            [MarshalAs(UnmanagedType.Bool)] public bool fReturnUnknown;
            [MarshalAs(UnmanagedType.Bool)] public bool fReturnConnected;
            [MarshalAs(UnmanagedType.Bool)] public bool fIssueInquiry;
            public byte cTimeoutMultiplier;
            public IntPtr hRadio;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct BLUETOOTH_DEVICE_INFO
        {
            public uint dwSize;
            public ulong Address;
            public uint ulClassofDevice;
            [MarshalAs(UnmanagedType.Bool)] public bool fConnected;
            [MarshalAs(UnmanagedType.Bool)] public bool fRemembered;
            [MarshalAs(UnmanagedType.Bool)] public bool fAuthenticated;
            public SYSTEMTIME stLastSeen;
            public SYSTEMTIME stLastUsed;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)]
            public string szName;
        }

        // ws2bth.h wraps SOCKADDR_BTH in pshpack1.h (exact size = 30 bytes).
        [StructLayout(LayoutKind.Sequential, Pack = 1)]
        private struct SOCKADDR_BTH
        {
            public ushort addressFamily;
            public ulong btAddr;
            public Guid serviceClassId;
            public uint port;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct WSADATA
        {
            public ushort wVersion;
            public ushort wHighVersion;
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 257)]
            public byte[] szDescription;
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 129)]
            public byte[] szSystemStatus;
            public ushort iMaxSockets;
            public ushort iMaxUdpDg;
            public IntPtr lpVendorInfo;
        }

        [DllImport("BluetoothAPIs.dll", SetLastError = true)]
        private static extern IntPtr BluetoothFindFirstDevice(
            ref BLUETOOTH_DEVICE_SEARCH_PARAMS pbtsp,
            ref BLUETOOTH_DEVICE_INFO pbtdi);

        [DllImport("BluetoothAPIs.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool BluetoothFindNextDevice(
            IntPtr hFind,
            ref BLUETOOTH_DEVICE_INFO pbtdi);

        [DllImport("BluetoothAPIs.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool BluetoothFindDeviceClose(IntPtr hFind);

        [DllImport("ws2_32.dll", SetLastError = false)]
        private static extern int WSAStartup(ushort wVersionRequested, out WSADATA lpWSAData);

        [DllImport("ws2_32.dll", SetLastError = false)]
        private static extern int WSACleanup();

        [DllImport("ws2_32.dll", SetLastError = false)]
        private static extern int WSAGetLastError();

        [DllImport("ws2_32.dll", SetLastError = false)]
        private static extern IntPtr socket(int af, int type, int protocol);

        [DllImport("ws2_32.dll", SetLastError = false)]
        private static extern int connect(IntPtr s, ref SOCKADDR_BTH name, int namelen);

        [DllImport("ws2_32.dll", SetLastError = false)]
        private static extern int recv(IntPtr s, byte[] buf, int len, int flags);

        [DllImport("ws2_32.dll", SetLastError = false)]
        private static extern int send(IntPtr s, byte[] buf, int len, int flags);

        [DllImport("ws2_32.dll", SetLastError = false)]
        private static extern int shutdown(IntPtr s, int how);

        [DllImport("ws2_32.dll", SetLastError = false)]
        private static extern int closesocket(IntPtr s);

        [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

        [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

        [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
        private static extern int GetMessageW(out MSG message, IntPtr hWnd, uint min, uint max);

        [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool PeekMessageW(out MSG message, IntPtr hWnd, uint min, uint max, uint remove);

        [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool PostThreadMessageW(uint threadId, uint message, UIntPtr wParam, IntPtr lParam);

        [DllImport("kernel32.dll", ExactSpelling = true)]
        private static extern uint GetCurrentThreadId();

        private static int Main(string[] args)
        {
            if (args == null || args.Length == 0)
            {
                WriteErr("Usage: WearEarsBtBridge.exe list | connect <MAC> <UUID|CHANNEL> | check | hotkeys");
                return 2;
            }

            string cmd = args[0].Trim().ToLowerInvariant();
            if (cmd == "list")
            {
                return RunList();
            }
            if (cmd == "connect")
            {
                if (args.Length < 3)
                {
                    WriteErr("Usage: WearEarsBtBridge.exe connect <MAC> <UUID|CHANNEL>");
                    return 2;
                }
                return RunConnect(args[1], args[2]);
            }
            if (cmd == "check")
            {
                int sz = Marshal.SizeOf(typeof(SOCKADDR_BTH));
                WriteOutUtf8("OK|SOCKADDR_BTH=" + sz + "|PTR=" + IntPtr.Size + "\n");
                return sz == 30 ? 0 : 3;
            }
            if (cmd == "hotkeys")
            {
                return RunHotkeys();
            }

            WriteErr("Unknown command: " + args[0]);
            return 2;
        }

        private static int RunHotkeys()
        {
            uint mainThread = GetCurrentThreadId();
            MSG message;
            // Create the queue before starting the stdin listener, even if stdin is already closed.
            PeekMessageW(out message, IntPtr.Zero, WM_USER, WM_USER, 0);
            var registered = new bool[HotkeyActions.Length];
            try
            {
                using (var stdout = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)))
                {
                    stdout.NewLine = "\n";
                    stdout.AutoFlush = true;
                    int count = 0;
                    for (int i = 0; i < HotkeyActions.Length; i++)
                    {
                        registered[i] = RegisterHotKey(IntPtr.Zero, i + 1, HOTKEY_MODIFIERS, HotkeyKeys[i]);
                        if (registered[i]) count++;
                        stdout.WriteLine("REG|" + HotkeyActions[i] + (registered[i] ? "|OK" : "|BUSY"));
                    }
                    stdout.WriteLine("READY|" + count.ToString(CultureInfo.InvariantCulture)
                        + "|" + registered.Length.ToString(CultureInfo.InvariantCulture));

                    var listener = new Thread(() =>
                    {
                        try
                        {
                            string line;
                            while ((line = Console.ReadLine()) != null && line != "QUIT") { }
                        }
                        catch (IOException) { }
                        finally
                        {
                            PostThreadMessageW(mainThread, WM_QUIT, UIntPtr.Zero, IntPtr.Zero);
                        }
                    });
                    listener.IsBackground = true;
                    listener.Start();

                    int result;
                    while ((result = GetMessageW(out message, IntPtr.Zero, 0, 0)) > 0)
                    {
                        if (message.message != WM_HOTKEY) continue;
                        ulong id = message.wParam.ToUInt64();
                        if (id >= 1 && id <= (ulong)registered.Length && registered[(int)id - 1])
                        {
                            stdout.WriteLine("HOTKEY|" + HotkeyActions[(int)id - 1]);
                        }
                    }
                    if (result == 0) return 0;
                    WriteErr("GetMessageW failed: " + Marshal.GetLastWin32Error());
                    return 1;
                }
            }
            catch (IOException ex)
            {
                WriteErr("Hotkey output failed: " + ex.Message);
                return 1;
            }
            finally
            {
                for (int i = 0; i < registered.Length; i++)
                {
                    if (registered[i]) UnregisterHotKey(IntPtr.Zero, i + 1);
                }
            }
        }

        private static int RunList()
        {
            var search = new BLUETOOTH_DEVICE_SEARCH_PARAMS();
            search.dwSize = (uint)Marshal.SizeOf(typeof(BLUETOOTH_DEVICE_SEARCH_PARAMS));
            search.fReturnAuthenticated = true;
            search.fReturnRemembered = true;
            search.fReturnUnknown = false;
            search.fReturnConnected = true;
            search.fIssueInquiry = false;
            search.cTimeoutMultiplier = 0;
            search.hRadio = IntPtr.Zero;

            var info = new BLUETOOTH_DEVICE_INFO();
            info.dwSize = (uint)Marshal.SizeOf(typeof(BLUETOOTH_DEVICE_INFO));

            IntPtr hFind = IntPtr.Zero;
            try
            {
                hFind = BluetoothFindFirstDevice(ref search, ref info);
            }
            catch (Exception ex)
            {
                WriteErr("BluetoothFindFirstDevice failed: " + ex.Message);
                return 1;
            }

            if (hFind == IntPtr.Zero)
            {
                int err = Marshal.GetLastWin32Error();
                // ERROR_NO_MORE_ITEMS (259) or ERROR_NOT_FOUND (1168) simply means 0 devices.
                if (err == 259 || err == 1168 || err == 0)
                {
                    return 0;
                }
                WriteErr("BluetoothFindFirstDevice Win32Error=" + err);
                return 1;
            }

            var sb = new StringBuilder();
            try
            {
                do
                {
                    string mac = FormatMac(info.Address);
                    string name = SanitizeField(info.szName);
                    if (string.IsNullOrEmpty(name))
                    {
                        name = mac;
                    }
                    sb.Append(mac)
                      .Append('|').Append(name)
                      .Append('|').Append(info.fConnected ? '1' : '0')
                      .Append('|').Append(info.fRemembered ? '1' : '0')
                      .Append('\n');

                    info = new BLUETOOTH_DEVICE_INFO();
                    info.dwSize = (uint)Marshal.SizeOf(typeof(BLUETOOTH_DEVICE_INFO));
                }
                while (BluetoothFindNextDevice(hFind, ref info));
            }
            finally
            {
                BluetoothFindDeviceClose(hFind);
            }

            WriteOutUtf8(sb.ToString());
            return 0;
        }

        private static int RunConnect(string macText, string targetText)
        {
            ulong btAddr;
            if (!TryParseMac(macText, out btAddr))
            {
                WriteErr("Invalid MAC address: " + macText);
                return 2;
            }

            Guid serviceGuid = Guid.Empty;
            uint rfcommPort = 0;
            uint parsedPort;
            if (uint.TryParse(targetText, NumberStyles.Integer, CultureInfo.InvariantCulture, out parsedPort))
            {
                if (parsedPort < 1 || parsedPort > 30)
                {
                    WriteErr("RFCOMM channel out of range (1..30): " + targetText);
                    return 2;
                }
                rfcommPort = parsedPort;
            }
            else
            {
                try
                {
                    serviceGuid = new Guid(targetText);
                }
                catch (Exception)
                {
                    WriteErr("Invalid UUID or RFCOMM channel: " + targetText);
                    return 2;
                }
            }

            int sockAddrSize = Marshal.SizeOf(typeof(SOCKADDR_BTH));
            if (sockAddrSize != 30)
            {
                WriteErr("Unexpected SOCKADDR_BTH size: " + sockAddrSize);
                return 3;
            }

            WSADATA wsaData;
            int wsaRes = WSAStartup(0x0202, out wsaData);
            if (wsaRes != 0)
            {
                WriteErr("WSAStartup failed: " + wsaRes);
                return 1;
            }

            IntPtr sock = INVALID_SOCKET;
            try
            {
                sock = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
                if (sock == INVALID_SOCKET)
                {
                    WriteErr("socket(AF_BTH) failed: WSA=" + WSAGetLastError());
                    return 1;
                }

                var addr = new SOCKADDR_BTH();
                addr.addressFamily = AF_BTH;
                addr.btAddr = btAddr;
                addr.serviceClassId = serviceGuid;
                addr.port = rfcommPort;

                int connRes = connect(sock, ref addr, sockAddrSize);
                if (connRes != 0)
                {
                    int wsaErr = WSAGetLastError();
                    WriteErr("RFCOMM connect failed: WSA=" + wsaErr);
                    return 1;
                }

                WriteErr("CONNECTED");

                int closedFlag = 0;
                var done = new ManualResetEventSlim(false);
                Stream stdin = Console.OpenStandardInput();
                Stream stdout = Console.OpenStandardOutput();

                Thread upThread = new Thread(() =>
                {
                    byte[] buf = new byte[1024];
                    try
                    {
                        while (Volatile.Read(ref closedFlag) == 0)
                        {
                            int n = stdin.Read(buf, 0, buf.Length);
                            if (n <= 0) break;
                            int sentTotal = 0;
                            while (sentTotal < n)
                            {
                                int chunkLen = n - sentTotal;
                                byte[] slice;
                                if (sentTotal == 0)
                                {
                                    slice = buf;
                                }
                                else
                                {
                                    slice = new byte[chunkLen];
                                    Buffer.BlockCopy(buf, sentTotal, slice, 0, chunkLen);
                                }
                                int sent = send(sock, slice, chunkLen, 0);
                                if (sent <= 0)
                                {
                                    return;
                                }
                                sentTotal += sent;
                            }
                        }
                    }
                    catch (Exception)
                    {
                    }
                    finally
                    {
                        if (Interlocked.Exchange(ref closedFlag, 1) == 0)
                        {
                            shutdown(sock, SD_BOTH);
                        }
                        done.Set();
                    }
                });
                upThread.IsBackground = true;
                upThread.Start();

                Thread downThread = new Thread(() =>
                {
                    byte[] buf = new byte[1024];
                    try
                    {
                        while (Volatile.Read(ref closedFlag) == 0)
                        {
                            int n = recv(sock, buf, buf.Length, 0);
                            if (n <= 0) break;
                            stdout.Write(buf, 0, n);
                            stdout.Flush();
                        }
                    }
                    catch (Exception)
                    {
                    }
                    finally
                    {
                        if (Interlocked.Exchange(ref closedFlag, 1) == 0)
                        {
                            shutdown(sock, SD_BOTH);
                        }
                        done.Set();
                    }
                });
                downThread.IsBackground = true;
                downThread.Start();

                done.Wait();
                return 0;
            }
            finally
            {
                if (sock != INVALID_SOCKET)
                {
                    try { shutdown(sock, SD_BOTH); } catch { }
                    try { closesocket(sock); } catch { }
                }
                try { WSACleanup(); } catch { }
            }
        }

        private static string FormatMac(ulong addr)
        {
            return string.Format(
                CultureInfo.InvariantCulture,
                "{0:X2}:{1:X2}:{2:X2}:{3:X2}:{4:X2}:{5:X2}",
                (addr >> 40) & 0xFF,
                (addr >> 32) & 0xFF,
                (addr >> 24) & 0xFF,
                (addr >> 16) & 0xFF,
                (addr >> 8) & 0xFF,
                addr & 0xFF);
        }

        private static bool TryParseMac(string raw, out ulong addr)
        {
            addr = 0;
            if (string.IsNullOrWhiteSpace(raw)) return false;
            string hex = raw.Trim().Replace(":", "").Replace("-", "");
            if (hex.Length != 12) return false;
            return ulong.TryParse(hex, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out addr);
        }

        private static string SanitizeField(string s)
        {
            if (string.IsNullOrEmpty(s)) return "";
            var sb = new StringBuilder(s.Length);
            for (int i = 0; i < s.Length; i++)
            {
                char c = s[i];
                if (c == '|' || c == '\r' || c == '\n' || char.IsControl(c)) continue;
                sb.Append(c);
            }
            return sb.ToString().Trim();
        }

        private static void WriteOutUtf8(string text)
        {
            if (string.IsNullOrEmpty(text)) return;
            byte[] bytes = new UTF8Encoding(false).GetBytes(text);
            using (Stream stdout = Console.OpenStandardOutput())
            {
                stdout.Write(bytes, 0, bytes.Length);
                stdout.Flush();
            }
        }

        private static void WriteErr(string line)
        {
            try
            {
                byte[] bytes = new UTF8Encoding(false).GetBytes((line ?? "") + "\n");
                Stream stderr = Console.OpenStandardError();
                stderr.Write(bytes, 0, bytes.Length);
                stderr.Flush();
            }
            catch (Exception)
            {
            }
        }
    }
}
