export interface KeyInfo {
    instance: string
    keyId: string
    family: string
}

export interface UpdateKeyRequest {
    family: string
    keyId: string
}

export interface ActiveKeyRequest {
    family: string
}
