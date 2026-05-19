$ADMIN = "43ab260c-9788-4952-8c70-fde3dd061ec6"
$TOKEN = "79742973-9b75-4d46-a101-86dafb234458"
$BASE  = "http://127.0.0.1:3010"

function Fx($method, $url, $token, $obj) {
    $req = [System.Net.WebRequest]::Create($url)
    $req.Method = $method
    if ($token) { $req.Headers.Add("Authorization", "Bearer $token") }
    if ($obj) {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Compress))
        $req.ContentType = "application/json"
        $req.ContentLength = $bytes.Length
        $s = $req.GetRequestStream(); $s.Write($bytes,0,$bytes.Length); $s.Close()
    } else { $req.ContentLength = 0 }
    try {
        $res = $req.GetResponse()
        $code = [int]$res.StatusCode
        $body = (New-Object System.IO.StreamReader($res.GetResponseStream())).ReadToEnd()
    } catch {
        $code = [int]$_.Exception.Response.StatusCode
        $body = (New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd()
    }
    Write-Host "[$method $code] $body"
}

Write-Host "--- deposit diamond x10 ---"
Fx POST "$BASE/api/deposit" $TOKEN @{ item="diamond"; amount=10 }

Write-Host "--- state after deposit ---"
Fx GET "$BASE/api/state" $TOKEN $null

Write-Host "--- limit BUY price=4 amount=5 ---"
Fx POST "$BASE/api/order" $TOKEN @{ pair_id="DIAMOND_EMERALD"; side="buy"; type="limit"; price="4"; amount="5" }

Write-Host "--- state after order ---"
Fx GET "$BASE/api/state" $TOKEN $null

Write-Host "--- orderbook ---"
Fx GET "$BASE/api/orderbook?pair=DIAMOND_EMERALD" $null $null

Write-Host "--- withdraw diamond x3 ---"
Fx POST "$BASE/api/withdraw" $TOKEN @{ item="diamond"; amount=3 }

Write-Host "--- state after withdraw ---"
Fx GET "$BASE/api/state" $TOKEN $null
