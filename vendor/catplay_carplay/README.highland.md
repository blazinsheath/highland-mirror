This directory contains catplay_carplay from cfraz89/catplay revision
2615e14b69230d04205e8de126b6d64346787cc0 (carplay/catplay_carplay).
Source: https://github.com/cfraz89/catplay/tree/2615e14b69230d04205e8de126b6d64346787cc0/carplay/catplay_carplay

Local changes: Cargo path dependencies use the original pinned git source; the
workspace patches this package and catplay_audio. AirPlayRtspClient adds one public
request method forwarding through its existing paired RTSP queue and timeout.
All pairing, video, UDP, framing and encryption implementations are unchanged.
The added method enables a bounded receiver-volume query on the active connection.
Original upstream notices and source comments are retained.
