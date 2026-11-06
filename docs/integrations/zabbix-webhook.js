// Easy ITSM: тип оповещения «Webhook» для Zabbix 6.0+.
var params = JSON.parse(value);
var body = {
  event_id: params.event_id,
  trigger_id: params.trigger_id,
  status: params.status,
  value: params.value,
  severity: params.severity,
  name: params.name,
  host: params.host,
  message: params.message,
  tags: params.tags,
  started_at: params.started_at,
  url: params.url_event
};
var req = new HttpRequest();
req.addHeader('Content-Type: application/json');
req.addHeader('Authorization: Bearer ' + params.token);
var resp = req.post(params.url, JSON.stringify(body));
if (req.getStatus() < 200 || req.getStatus() >= 300) {
  throw 'Easy ITSM: HTTP ' + req.getStatus() + ' ' + resp;
}
return 'OK';
