using System.Collections.Concurrent;
using System.Net;
using System.Text;
using System.Text.Json;
using MQTTnet;
using MQTTnet.Client;
using MQTTnet.Protocol;
using MQTTnet.Server;

// Temporary status-only broker. Uses the existing destination; sends no device commands.
var settingsPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "jetkvm-monitor-switcher", "settings.json");
using var settings = JsonDocument.Parse(File.ReadAllText(settingsPath));
var deviceIp = settings.RootElement.GetProperty("JetIp").GetString()!;
var bindIp = settings.RootElement.GetProperty("ReceiverIp").GetString()!;
var fixture = args.Contains("--fixture");
if (fixture) bindIp = "127.0.0.1";
var localRelay = args.Contains("--local-relay");
var port = fixture ? 1886 : localRelay ? 1885 : settings.RootElement.GetProperty("ReceiverPort").GetInt32();
var factory = new MqttFactory();
using var server = factory.CreateMqttServer(new MqttServerOptionsBuilder().WithDefaultEndpoint()
    .WithDefaultEndpointBoundIPAddress(IPAddress.Parse(bindIp)).WithDefaultEndpointBoundIPV6Address(IPAddress.IPv6Loopback).WithDefaultEndpointPort(port).Build());
var observations = new ConcurrentDictionary<string, JsonElement>();
var liveCount = 0;
var retainedCount = 0;
var deviceConnected = false;
var commandsBlocked = 0;
var probeId = "jetkvm-readonly-status-probe";
bool AllowedTopic(string topic) => new[] { "/status", "/atx/state", "/dc/state", "/sessions/state" }.Any(topic.EndsWith);
server.ValidatingConnectionAsync += e => {
    var fromDevice = (fixture && e.ClientId == "fixture-device" && e.Endpoint.StartsWith("127.0.0.1:", StringComparison.Ordinal)) || e.Endpoint.StartsWith(deviceIp + ":", StringComparison.Ordinal) ||
        (localRelay && e.ClientId != probeId && e.Endpoint.StartsWith(bindIp + ":", StringComparison.Ordinal));
    var fromProbe = e.ClientId == probeId && e.Endpoint.StartsWith(bindIp + ":", StringComparison.Ordinal);
    if (!fromDevice && !fromProbe) e.ReasonCode = MqttConnectReasonCode.NotAuthorized;
    if (fromDevice) deviceConnected = true;
    return Task.CompletedTask;
};
server.InterceptingSubscriptionAsync += e => {
    if (e.ClientId != probeId) e.Response.ReasonCode = MqttSubscribeReasonCode.NotAuthorized;
    return Task.CompletedTask;
};
server.InterceptingPublishAsync += e => {
    e.ProcessPublish = e.ClientId != probeId && AllowedTopic(e.ApplicationMessage.Topic);
    if (e.ApplicationMessage.Topic.EndsWith("/set")) { e.ProcessPublish = false; Interlocked.Increment(ref commandsBlocked); }
    return Task.CompletedTask;
};
using var client = factory.CreateMqttClient();
client.ApplicationMessageReceivedAsync += e => {
    try {
        using var document = JsonDocument.Parse(e.ApplicationMessage.PayloadSegment);
        var topic = e.ApplicationMessage.Topic;
        // Retain only the relevant scalar fields; never store MQTT credentials or device identifiers.
        var suffix = new[] { "/atx/state", "/dc/state", "/sessions/state", "/status" }.First(topic.EndsWith);
        var allowed = new[] { "online", "power", "hdd", "isOn", "active_sessions" };
        var values = document.RootElement.EnumerateObject().Where(p => allowed.Contains(p.Name)).ToDictionary(p => p.Name, p => p.Value.Clone());
        observations[suffix] = JsonSerializer.SerializeToElement(values);
        if (e.ApplicationMessage.Retain) Interlocked.Increment(ref retainedCount); else Interlocked.Increment(ref liveCount);
    } catch (Exception ex) when (ex is JsonException or InvalidOperationException) { Console.WriteLine("Invalid status payload"); }
    return Task.CompletedTask;
};
var reportPath = args.FirstOrDefault() ?? "verification/mqtt-live-probe.json";
try {
    await server.StartAsync();
    Console.WriteLine("Temporary read-only MQTT receiver started; waiting up to 45 seconds for existing device reconnect.");
    var options = new MqttClientOptionsBuilder().WithClientId(probeId).WithTcpServer(bindIp,port).Build();
    await client.ConnectAsync(options);
    await client.SubscribeAsync(factory.CreateSubscribeOptionsBuilder().WithTopicFilter("#", MqttQualityOfServiceLevel.AtLeastOnce).Build());
    using var publisher = factory.CreateMqttClient();
    if (fixture) {
        await publisher.ConnectAsync(new MqttClientOptionsBuilder().WithClientId("fixture-device").WithTcpServer(bindIp,port).Build());
        foreach (var (suffix,payload) in new[] { ("status","{\"online\":true}"), ("atx/state","{\"power\":true,\"hdd\":false}"), ("sessions/state","{\"active_sessions\":0}") })
            await publisher.PublishAsync(new MqttApplicationMessageBuilder().WithTopic("fixture/"+suffix).WithPayload(payload).WithRetainFlag().WithQualityOfServiceLevel(MqttQualityOfServiceLevel.AtLeastOnce).Build());
    }
    var deadline = DateTime.UtcNow.AddSeconds(45);
    while (DateTime.UtcNow < deadline && !(observations.ContainsKey("/sessions/state") && (observations.ContainsKey("/atx/state") || observations.ContainsKey("/dc/state")))) await Task.Delay(500);
    // A second subscription proves the broker can supply the device's retained snapshot on app restart.
    await client.DisconnectAsync();
    await client.ConnectAsync(options);
    await client.SubscribeAsync(factory.CreateSubscribeOptionsBuilder().WithTopicFilter("#", MqttQualityOfServiceLevel.AtLeastOnce).Build());
    await Task.Delay(1500);
    var report = new { utc = DateTimeOffset.UtcNow, fixture, deviceConnected, liveCount, retainedCount,
        states = observations, commandsSent = 0, commandsBlocked,
        result = deviceConnected && observations.ContainsKey("/atx/state") && retainedCount > 0 ? (fixture ? "PASS_LOCAL_FIXTURE_ONLY" : "PASS_ATX_MQTT_WITHOUT_WEBRTC") : "INCOMPLETE",
        settingsChanged = false };
    var json = JsonSerializer.Serialize(report,new JsonSerializerOptions { WriteIndented = true });
    File.WriteAllText(reportPath,json); Console.WriteLine(json);
    if (publisher.IsConnected) await publisher.DisconnectAsync();
} finally {
    if (client.IsConnected) await client.DisconnectAsync();
    await server.StopAsync();
    Console.WriteLine("Temporary MQTT receiver stopped.");
}
