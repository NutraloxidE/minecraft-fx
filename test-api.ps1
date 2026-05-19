$ADMIN = "43ab260c-9788-4952-8c70-fde3dd061ec6"
$TOKEN = "79742973-9b75-4d46-a101-86dafb234458"
$BASE = "http://127.0.0.1:3010"

function Post($url, $token, $obj) {
    $json = $obj | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $req = [System.Net.WebRequest]::Create($url)
    $req.Method = "POST"
    $req.ContentType = "application/json"
    $req.Headers.Add("Authorization", "Bearer $token")
    $req.ContentLength = $bytes.Length
    $stream = $req.GetRequestStream()
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Close()
    try {
        $res = $req.GetResponse()
        $code = [int]$res.StatusCode
        $body = New-Object System.IO.StreamReader($res.GetResponseStream()) | ForEach-Object { $_.ReadToEnd() }
        Write-Host "HTTP $code : $body"
    } catch [System.Net.WebException] {
        $code = [int]$_.Exception.Response.StatusCode
        $body = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream()) | ForEach-Object { $_.ReadToEnd() }
        Write-Host "HTTP $code : $body"
    }
}

function Get_($url, $token) {
    $req = [System.Net.WebRequest]::Create($url)
    $req.Headers.Add("Authorization", "Bearer $token")
    try {
        $res = $req.GetResponse()
        $code = [int]$res.StatusCode
        $body = New-Object System.IO.StreamReader($res.GetResponseStream()) | ForEach-Object { $_.ReadToEnd() }
        Write-Host "HTTP $code : $body"
    } catch [System.Net.WebException] {
        $code = [int]$_.Exception.Response.StatusCode
        $body = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream()) | ForEach-Object { $_.ReadToEnd() }
        Write-Host "HTTP $code : $body"
    }
}

function Patch_($url, $token, $obj) {
    $json = $obj | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $req = [System.Net.WebRequest]::Create($url)
    $req.Method = "PATCH"
    $req.ContentType = "application/json"
    $req.Headers.Add("Authorization", "Bearer $token")
    $req.ContentLength = $bytes.Length
    $stream = $req.GetRequestStream()
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Close()
    try {
        $res = $req.GetResponse()
        $code = [int]$res.StatusCode
        $body = New-Object System.IO.StreamReader($res.GetResponseStream()) | ForEach-Object { $_.ReadToEnd() }
        Write-Host "HTTP $code : $body"
    } catch [System.Net.WebException] {
        $code = [int]$_.Exception.Response.StatusCode
        $body = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream()) | ForEach-Object { $_.ReadToEnd() }
        Write-Host "HTTP $code : $body"
    }
}

function Delete_($url, $token) {
    $req = [System.Net.WebRequest]::Create($url)
    $req.Method = "DELETE"
    $req.Headers.Add("Authorization", "Bearer $token")
    $req.ContentLength = 0
    try {
        $res = $req.GetResponse()
        $code = [int]$res.StatusCode
        $body = New-Object System.IO.StreamReader($res.GetResponseStream()) | ForEach-Object { $_.ReadToEnd() }
        Write-Host "HTTP $code : $body"
    } catch [System.Net.WebException] {
        $code = [int]$_.Exception.Response.StatusCode
        $body = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream()) | ForEach-Object { $_.ReadToEnd() }
        Write-Host "HTTP $code : $body"
    }
}

Write-Host "`n=== 1. 管理者: ペア一覧 ==="
Get_ "$BASE/api/admin/pairs" $ADMIN

Write-Host "`n=== 2. 管理者: ペア編集 (min_amount=5) ==="
Patch_ "$BASE/api/admin/pairs/DIAMOND_EMERALD" $ADMIN @{ min_amount = "5" }

Write-Host "`n=== 3. 管理者: 編集後ペア一覧 ==="
Get_ "$BASE/api/admin/pairs" $ADMIN

Write-Host "`n=== 4. 公開API: ペア一覧 ==="
Get_ "$BASE/api/pairs" ""

Write-Host "`n=== 5. 公開API: 板 ==="
Get_ "$BASE/api/orderbook?pair=DIAMOND_EMERALD" ""

Write-Host "`n=== 6. プレイヤー: 入金リクエスト (diamond x10) ==="
Post "$BASE/api/deposit" $TOKEN @{ item = "diamond"; amount = 10 }

Write-Host "`n=== 7. プレイヤー: state確認 ==="
Get_ "$BASE/api/state" $TOKEN

Write-Host "`n=== 8. プレイヤー: 残高不足で注文 → 400期待 ==="
Post "$BASE/api/order" $TOKEN @{ pair = "DIAMOND_EMERALD"; side = "buy"; type = "limit"; price = "4"; amount = "100" }

Write-Host "`n=== 9. 管理者: ペア削除 ==="
Delete_ "$BASE/api/admin/pairs/DIAMOND_EMERALD" $ADMIN

Write-Host "`n=== 10. 削除後ペア一覧 (空を期待) ==="
Get_ "$BASE/api/admin/pairs" $ADMIN

Write-Host "`n=== 11. 管理者: ペア再作成 ==="
Post "$BASE/api/admin/pairs" $ADMIN @{ id = "DIAMOND_EMERALD"; base = "diamond"; quote = "emerald"; min_amount = "1"; min_price = "1"; enabled = $true }

Write-Host "done"
