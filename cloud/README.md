# ClipForge Cloud

Open Google Colab and run:

```python
!git clone https://github.com/miang20/clipforge-android.git
%cd clipforge-android
!apt-get -qq update && apt-get -qq install -y ffmpeg
!pip -q install -r cloud/requirements.txt
!python cloud/app.py
```

Note: The cloud code must be merged to main before these instructions work. Colab is not a 24/7 host. The current selection algorithm is heuristic; full semantic hook/payoff analysis is not implemented.
