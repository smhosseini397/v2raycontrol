<?php
// Happ Guard relay for farshkashanco.com — PHP 7.4+
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('X-Content-Type-Options: nosniff');

$SECRET = '4HKOPI0YjpIw4pkZuV18wwySS5luwz9-w_ZDlgWVa1sghCr1T6ePD_L-WtOPyiTh'; // Keep identical only in this file and both Happ Guard installs.
if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    http_response_code(405); echo json_encode(['ok'=>false,'error'=>'POST only']); exit;
}
$in = json_decode((string)file_get_contents('php://input'), true);
if (!is_array($in)) { http_response_code(400); echo json_encode(['ok'=>false,'error'=>'bad json']); exit; }
$key = (string)($in['key'] ?? '');
if ($key === '' || !hash_equals($SECRET, $key)) { http_response_code(403); echo json_encode(['ok'=>false,'error'=>'forbidden']); exit; }
$device = preg_replace('/[^a-zA-Z0-9._-]/', '', (string)($in['device'] ?? ''));
$action = strtoupper((string)($in['action'] ?? 'STATUS'));
if ($device === '' || strlen($device) > 100 || !in_array($action, ['CLAIM','RELEASE','ACK','STATUS'], true)) {
    http_response_code(400); echo json_encode(['ok'=>false,'error'=>'bad request']); exit;
}

// Store state in a separately protected subfolder (does not overwrite the site's root web.config).
$dataDir = __DIR__ . '/data';
if (!is_dir($dataDir) && !@mkdir($dataDir, 0750, true) && !is_dir($dataDir)) { http_response_code(500); echo json_encode(['ok'=>false,'error'=>'data directory unavailable']); exit; }
$file = $dataDir . '/guard-state.json';
$fp = fopen($file, 'c+');
if (!$fp) { http_response_code(500); echo json_encode(['ok'=>false,'error'=>'storage unavailable']); exit; }
if (!flock($fp, LOCK_EX)) { fclose($fp); http_response_code(503); echo json_encode(['ok'=>false,'error'=>'lock unavailable']); exit; }
rewind($fp);
$raw = stream_get_contents($fp);
$state = json_decode($raw ?: '{}', true);
if (!is_array($state)) $state = [];
$now = time();
$owner = $state['active'] ?? null;
$activeAt = (int)($state['active_at'] ?? 0);
// A device is considered stale only after 45 seconds without a heartbeat.
if ($owner !== null && $now - $activeAt > 45) {
    $state = ['active'=>null, 'active_at'=>0, 'disconnect'=>null];
    $owner = null;
}
$out = ['ok'=>true, 'disconnect'=>false, 'owner'=>$owner];
if ($action === 'CLAIM') {
    $old = $owner;
    if ($old !== null && $old !== $device) $state['disconnect'] = $old;
    $state['active'] = $device;
    $state['active_at'] = $now;
    $out['owner'] = $device;
    $out['previous_device'] = $old;
} elseif ($action === 'RELEASE') {
    if (($state['active'] ?? null) === $device) { $state['active'] = null; $state['active_at'] = 0; }
    if (($state['disconnect'] ?? null) === $device) $state['disconnect'] = null;
    $out['owner'] = $state['active'] ?? null;
} elseif ($action === 'ACK') {
    if (($state['disconnect'] ?? null) === $device) $state['disconnect'] = null;
    $out['owner'] = $state['active'] ?? null;
} else {
    if (($state['active'] ?? null) === $device) $state['active_at'] = $now;
    $out['disconnect'] = (($state['disconnect'] ?? null) === $device);
    $out['owner'] = $state['active'] ?? null;
}
rewind($fp); ftruncate($fp, 0);
fwrite($fp, json_encode($state, JSON_UNESCAPED_SLASHES)); fflush($fp);
flock($fp, LOCK_UN); fclose($fp);
echo json_encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
