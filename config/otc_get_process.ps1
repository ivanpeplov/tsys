
Get-Process -Name "Online Acquirer" -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process -Name "Online Administration" -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process -Name "Online Electronic Data Capture_Acquirer" -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process -Name "Online Host Security Module" -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process -Name "Online Issuer" -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process -Name "Online Parameters" -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process -Name "Online Sampler Export" -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process -Name "Online Sampler Import" -ErrorAction SilentlyContinue | Stop-Process -Force
