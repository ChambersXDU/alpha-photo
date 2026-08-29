# Sony a7C II media-transfer research audit

Target hardware:
- Sony ILCE-7CM2 / a7C II, firmware 2.0.1
- Android 16
- Wireless only: BLE -> camera Wi-Fi -> PTP/IP

This document is the research gate for media-transfer work. Code should not assume an
operation's behavior merely because it appears in DeviceInfo. Prefer, in order:

1. Sony Camera Control PTP 3 reference and ILCE-7CM2 compatibility matrix.
2. Real-device evidence from Alpha Photo.
3. Independent real-device implementations (especially CokeeZVE).
4. Clean-room protocol notes (CameraSync).
5. Generic PTP/MTP references and libgphoto2/Wireshark constants.

## Verified Alpha Photo facts

- BLE/GATT association works on ILCE-7CM2.
- Sony CC08 starts camera Wi-Fi.
- CC07 returns the Wi-Fi password.
- CC0C returns the BSSID.
- CC06 currently returns GATT status 0x90; Android network state supplies the SSID.
- Android joins the camera network and reaches 192.168.122.1:15740.
- PTP/IP command and event channels work.
- Sony SDIO phases 1/2/3 work.
- Content-transfer mode 0 -> 1 works.
- Standard GetStorageIDs -> real storage 0x10001 works.
- Standard GetObjectHandles(realStorageId, 0, 0) works.
- Standard GetObjectInfo works for all 182 objects in the current card.
- 180 objects are photo files.
- Standard GetThumb works for at least DSC02292.JPG and returned 126,718 bytes.
- Standard GetPartialObject (0x101B) returned 0x2009 for that same valid JPEG handle.
  0x2009 is standard PTP InvalidObjectHandle.
- 0x923B SDIO_GetCapturedDateList returned Sony 0xA106 Camera Status Error on this body.

## Official ILCE-7CM2 operation support

Sony Camera Control PTP 3 compatibility table lists ILCE-7CM2 support for:

- 0x1004 GetStorageIDs
- 0x1005 GetStorageInfo
- 0x1006 GetNumObjects
- 0x1007 GetObjectHandles
- 0x1008 GetObjectInfo
- 0x1009 GetObject
- 0x100A GetThumb
- 0x101B GetPartialObject
- 0x9803 GetObjectPropValue
- 0x9805 GetObjectPropList
- 0x9210 SDIO_OpenSession
- 0x9211 SDIO_GetPartialLargeObject
- 0x9212 SDIO_SetContentsTransferMode
- 0x9216 SDIO_GetVendorCodeVersion

Important nuance: official support for 0x101B does NOT mean it is valid with every object
handle in every function mode. The current a7C II content-transfer session returned
InvalidObjectHandle for a handle that succeeds with GetObjectInfo and GetThumb.

Sony's GetPartialObject documentation also explicitly recommends GetObject instead for
transfer performance.

## Sony content-transfer-mode sequence

Sony's PTP 3 reference describes the content-transfer path as:

PTP connected
-> SDIO_OpenSession(FunctionMode = Content Transfer)
-> authentication
-> SDIO_SetContentsTransferMode(ON)
-> StoreAdded
-> SDIE_DevicePropChanged
-> read Contents Transfer Enable Status (0xD295)
-> wait until current value == 1
-> ready to transfer content
-> SDIO_GetPartialLargeObject, etc.

Therefore a fixed sleep is only a pragmatic fallback. Product code should eventually gate
readiness on protocol state (StoreAdded / D295) rather than assuming 1500 ms is always enough.

## 0x9211 SDIO_GetPartialLargeObject

Sony PTP 3 reference:

- Operation code: 0x9211
- Param 1: ObjectHandle
- Param 2: Offset low 32 bits
- Param 3: Offset high 32 bits
- Param 4: Maximum bytes to obtain
- Data direction: camera -> host
- Response param 1: actual number of bytes sent
- Documented errors include OperationNotSupported, SessionNotOpen,
  InvalidTransactionID, DeviceBusy, ParameterNotSupported.

The reference does not state a universal maximum chunk size for 0x9211. Do not copy the
3 MiB limit from 0x923D without evidence; that limit belongs to a different Sony content
operation used by another transfer path.

## GetObject versus partial transfer

Independent CokeeZVE real-device work on Sony ZV-E10 content-transfer mode verified:

- Function mode 1 + 0x9212 ON is required for normal media storage browsing.
- GetStorageIDs must be used; 0xFFFFFFFF storage can fail.
- GetObjectHandles(real storage, 0, 0) works.
- GetObjectInfo works.
- GetThumb works.
- GetObject works for full-size JPEG and is used for imports.
- Their full-object implementation was changed to stream data in small blocks instead of
  buffering large PTP/IP DATA packets in memory.

This strongly supports Alpha Photo's standard-object-listing + streamed GetObject design.

Use 0x9211 for range access/resume only after direct a7C II verification. Do not assume
standard 0x101B and Sony 0x9211 are interchangeable in content-transfer mode.

## Thumbnail and preview strategy

Sony's generic PTP 3 text says GetThumb is not meaningful in the remote-control protocol,
but both CokeeZVE real-device testing and Alpha Photo content-transfer testing show that
GetThumb can work while browsing media in content-transfer mode.

Therefore:
- Treat GetThumb as a content/object capability, not a universal camera guarantee.
- Parse the thumbnail fields in ObjectInfo instead of discarding them.
- For a grid, prefer GetThumb when thumbnail metadata says one exists.
- For RAW-only full-screen preview, do not assume the embedded JPEG lives inside the first
  1 MiB of the ARW. A future range-reader should first parse enough TIFF/ARW metadata to
  discover the embedded preview offset/length, then request exactly that range via a
  verified partial-read operation.

## Event model for ILCE-7CM2

Sony PTP 3 compatibility table explicitly lists ILCE-7CM2 support for:

- 0x4004 StoreAdded
- 0x4005 StoreRemoved
- 0xC201 SDIE_ObjectAdded
- 0xC202 SDIE_ObjectRemoved
- 0xC203 SDIE_DevicePropChanged
- 0xC206 SDIE_CapturedEvent
- 0xC20D SDIE_ContentsTransferEvent
- 0xC234 SDIE_ContentInfoListChanged
- plus other vendor events not relevant to the first photo-browser MVP.

Critical semantics:
- 0xC201 SDIE_ObjectAdded:
  - Param 1 = ObjectHandle
  - Sony explicitly says the host may call GetObjectInfo with this handle.
  - This is the primary event candidate for "new photo appeared".
- 0xC206 SDIE_CapturedEvent:
  - no parameters.
  - signals capture, but does not itself identify the new object.
- 0xC234 SDIE_ContentInfoListChanged:
  - Param 1 = slot (1/2)
  - Param 2 = added/deleted/changed.
- 0xC20D SDIE_ContentsTransferEvent:
  - reports transfer-level busy/status errors; not a new-photo handle.

Do not design new-photo detection only around standard PTP 0x4002 ObjectAdded. Sony's
documented vendor event for a shot file ready to transfer is 0xC201.

## PTP/IP keepalive

CokeeZVE real-device investigation found Sony's PTP/IP ProbeRequest/ProbeResponse mechanism
is part of session liveness and that letting keepalive lapse can cause the camera to leave
the smartphone-operation state.

Alpha Photo already acknowledges camera ProbeRequest packets on both command and event
channels. Before productization, add an active keepalive/liveness policy only if a7C II
testing shows the camera expects initiator-originated probes during idle periods.

## Alternative Sony 0x923x media path

CameraSync documents another Sony media model:

- 0x923C SDIO_GetContentsInfoList
- 0x923D SDIO_GetContentsData
- 0x923E SDIO_GetContentsCompressedData
- 0x923F selected-on-camera transfer list

This path uses Sony content IDs / file IDs / slot IDs rather than standard PTP object
handles, and can provide Sony-generated thumbnail/screennail/original variants.

Alpha Photo's a7C II returned 0xA106 at the prerequisite 0x923B date-list step. Because
standard storage/object browsing is already proven, do not return to 0x923B/0x923C unless
a future requirement cannot be met with standard objects + 0x9211/GetObject.

## MTP object-property path

ILCE-7CM2 officially supports:
- 0x9803 GetObjectPropValue
- 0x9805 GetObjectPropList

CokeeZVE notes that Sony's official browse path can use GetObjectPropList and that folder
recursion via GetObjectHandles can be awkward on some bodies. Alpha Photo currently gets
all required photo objects from the flat real-storage handle list, so MTP property-list
support is not needed for MVP unless:
- folders/associations become necessary,
- HEIF/video metadata is insufficient in ObjectInfo,
- or a model does not expose a flat usable handle list.

## Response-code handling

At minimum decode these before more hardware probes:
- 0x2001 OK
- 0x2002 GeneralError
- 0x2007 IncompleteTransfer
- 0x2008 InvalidStorageID
- 0x2009 InvalidObjectHandle
- 0x200A DevicePropNotSupported
- 0x200B InvalidObjectFormatCode
- 0x2019 DeviceBusy
- 0x201F TransactionCanceled
- 0xA101..0xA106 Sony vendor errors, especially 0xA106 Camera Status Error.

Do not log known standard response codes as "Unknown".

## Implementation decisions after this audit

1. Keep standard storage/object enumeration.
2. Keep streamed GetObject as the baseline full-file transfer path.
3. Verify 0x9211 on the a7C II before depending on range/resume behavior.
4. Do not treat 0x101B as globally unsupported; record only that it failed in the current
   a7C II content-transfer context.
5. Add Sony vendor event constants/semantics before the next new-capture test.
6. Replace fixed content-transfer readiness sleep with explicit readiness observation when
   the property/event path is implemented.
7. Parse ObjectInfo thumbnail metadata before building the real grid.
8. Research ARW embedded-preview metadata before implementing RAW-only high-resolution
   preview; do not guess a fixed offset or first-chunk size.
9. Do not add 0x923B/0x923C retries or guessed workarounds.
10. Keep one serialized camera transaction pipeline; Sony bodies may reject/behave badly
    with concurrent PTP initiators/transactions.

## Research sources inspected

- Sony Camera Control PTP 3 reference mirrored in olkham/pysonycam:
  - operation list and ILCE-7CM2 compatibility matrix
  - content-transfer sequence
  - GetPartialObject notes
  - SDIO_GetPartialLargeObject parameter specification
  - event list and event parameter specifications
  - Contents Transfer Enable Status 0xD295
- Ahaitang/CokeeZVE:
  - real Sony content-transfer mode experiments
  - standard object listing
  - GetThumb/GetObject real-device behavior
  - streaming large-object implementation notes
  - PTP/IP keepalive behavior
- rock3r/CameraSync:
  - clean-room BLE/Wi-Fi handoff and alternate 0x923x transfer flow
- gphoto/libgphoto2:
  - Sony vendor opcode and object-format constants
- generic PTP/MTP references for standard response codes and object operations

Any future protocol change should update this audit first, then add a failing test, then
change production code.
