import json
import re
import traceback
import time
import base64
from urllib.parse import quote_plus, urlparse, parse_qsl
from datetime import datetime, date
from typing import Union
from concurrent.futures import ThreadPoolExecutor
import requests
from requests import Response
from bs4 import BeautifulSoup
from tzlocal import get_localzone
import pytz
import xbmc
import xbmcgui
import xbmcplugin
import xbmcvfs
import variables as var
from models import Item


class proxydt(datetime):

    @classmethod
    def strptime(cls, date_string, _format):
        return datetime(*(time.strptime(date_string, _format)[:6]))


datetime = proxydt


def log(message: str):
    return xbmc.log(str(message), xbmc.LOGINFO)

def container_refresh():
    xbmc.executebuiltin('Container.Refresh')

def get(url: str, referer: str='', headers=None, timeout:int=10) -> Response:
    headers = var.headers if headers is None else headers
    if referer:
        headers['Referer'] = headers['Origin'] = referer
    try:
        return requests.get(url, headers=headers, timeout=timeout)
        
    except:
        return requests.get(url.replace('https', 'http'), headers=headers, timeout=timeout, verify=False)

def get_soup(response: str) -> BeautifulSoup:
    return BeautifulSoup(response, 'html.parser')

def set_info(liz: xbmcgui.ListItem, infolabels: dict, cast: list=None):
    cast = cast or []
    i = liz.getVideoInfoTag()
    i.setMediaType(infolabels.get("mediatype", "video"))
    i.setTitle(infolabels.get("title", "Unknown"))
    i.setPlot(infolabels.get("plot", infolabels.get("title", "")))
    i.setTagLine(infolabels.get("tagline", ""))
    i.setPremiered(infolabels.get("premiered", ""))
    i.setGenres(infolabels.get("genre", []))
    i.setMpaa(infolabels.get("mpaa", ""))
    i.setDirectors(infolabels.get("director", []))
    i.setWriters(infolabels.get("writer", []))
    i.setRating(infolabels.get("rating", 0))
    i.setVotes(infolabels.get("votes", 0))
    i.setStudios(infolabels.get("studio", []))
    i.setCountries(infolabels.get("country", []))
    i.setSet(infolabels.get("set", ""))
    i.setTvShowStatus(infolabels.get("status", ""))
    i.setDuration(infolabels.get("duration", 0))
    i.setTrailer(infolabels.get("trailer", ""))

    cast_list = []
    for actor in cast:
        cast_list.append(xbmc.Actor(
            name=actor.get("name", ""),
            role=actor.get("role", ""),
            thumbnail=actor.get("thumbnail", "")
        ))
    i.setCast(cast_list)

def create_listitem(item: Union[Item, dict]):
    if isinstance(item, dict):
        item = Item(**item)
    is_folder = item.type == 'dir'
    title = item.title
    thumbnail = item.thumbnail
    fanart = item.fanart
    contextmenu = item.contextmenu
    is_media = item.is_media
    is_search = item.is_search
    list_item = xbmcgui.ListItem(label=title)
    list_item.setArt({'thumb': thumbnail, 'icon': thumbnail, 'poster': thumbnail, 'fanart': fanart})
    infolabels = {
        'mediatype': 'video',
        'title': title,
        'plot': title,
    }
    set_info(list_item, infolabels)
    if is_folder is False and is_media is True and is_search is False:
        list_item.setProperty('IsPlayable', 'true')
    list_item.addContextMenuItems(contextmenu)
    plugin_url = f'{var.plugin_url}?{item.url_encode()}'
    xbmcplugin.addDirectoryItem(var.handle, plugin_url, list_item, is_folder)

def ok_dialog(text: str):
    xbmcgui.Dialog().ok(var.addon_name, text)

def get_multilink(lists):
    if len(lists) == 1:
        return lists[1]
    elif not lists:
        var.notify_dialog(var.addon_name, 'No links were found.')
        var.system_exit()
        
    labels = [l[0] for l in lists]
    links = [l[1] for l in lists]
    ret = xbmcgui.Dialog().select('Choose a Link', labels)
    if ret == -1:
        var.system_exit()
    return links[ret]

def write_file(file_path, string):
    with open(file_path, 'w', encoding='utf-8', errors='ignore') as f:
        f.write(string)

def read_file(file_path):
    with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
        return f.read()

def fetch_channels():
    item_list = []
    try:
        response = get(var.channels_url)
        soup = get_soup(response.text)
        for card in soup.select('.card'):
            title = card.div.text
            href = f"{var.base_url}{card['href']}"
            channel_id = dict(parse_qsl(urlparse(href).query))['id']
            link = f'/stream/stream-{channel_id}.php'
            item_list.append(
                {
                    'title': title,
                    'link': link
                }
            )
            
    except Exception as e:
        log(f'Error fetching channels json: {e}')
        try:
            response = get(var.channels_url_old)
            soup = get_soup(response.text)
            channels = []
            for a in soup.find_all('a')[8:]:
                title = a.text
                link = a['href']
                if link in channels:
                    continue
                channels.append(link)
                
                item_list.append(
                    {
                        'title': title,
                        'link': link
                    }
                )
                
        except Exception as e:
            log(f'Error fetch old format channels: {e}')
            return json.loads(read_file(var.ch_bak_path))
    return item_list

def write_channels():
    if not xbmcvfs.exists(var.profile_path):
        xbmcvfs.mkdirs(var.profile_path)
    items = fetch_channels()
    write_file(var.ch_path, json.dumps(items))

def read_channels():
    if not xbmcvfs.exists(var.ch_path):
        write_channels()
    return json.loads(read_file(var.ch_path))

def fetch_schedule():
    schedule_dict = {}
    try:
        response = get(var.schedule_url)
        soup = get_soup(response.text)
        for schedule in soup.select('.schedule__day'):
            if categories := schedule.find_all(class_='schedule__category is-expanded'):
                s_date = schedule.select_one('.schedule__dayTitle').get_text(strip=True)
                s_date = s_date.split(' -', 1)[0].strip()
                dict_date = schedule_dict[s_date] = {}
            else:
                continue
            
            for cat in categories:
                cat_name = cat.select_one('.card__meta').get_text(strip=True)
                events = dict_date[cat_name] = []
                for event in cat.select('.schedule__event'):
                    e_time = event.select_one('.schedule__time').get_text(strip=True)
                    title = event.select_one('.schedule__eventTitle').get_text(strip=True)
                    
                    channels = []
                    for channel in event.select_one('.schedule__channels').find_all('a'):
                        link = f"{var.base_url}{channel['href']}"
                        channel_id = dict(parse_qsl(urlparse(link).query))['id']
                        channels.append({
                            'channel_name': channel.get_text(strip=True),
                            'channel_id': channel_id
                        })
                    
                    events.append({
                        'time': e_time,
                        'event': title,
                        'channels': channels,
                        'channels2': []
                    })
                
    except Exception as e:
        log(f'Failed to fetch schedule: {e}')
    
    return json.dumps(schedule_dict, indent=2)

def write_schedule():
    if not xbmcvfs.exists(var.profile_path):
        xbmcvfs.mkdirs(var.profile_path)
    schedule = fetch_schedule()
    write_file(var.schedule_path, schedule)

def read_schedule() -> dict:
    schedule = {}
    if not xbmcvfs.exists(var.schedule_path):
        write_schedule()
    try:
        schedule = json.loads(read_file(var.schedule_path))
    except:
        schedule = {}
    return schedule

def write_cat_schedule(string):
    write_file(var.cat_schedule_path, string)

def read_cat_schedule() -> list:
    return json.loads(read_file(var.cat_schedule_path))

def read_favourites() -> list:
    if not xbmcvfs.exists(var.fav_path):
        write_file(var.fav_path, json.dumps([]))
    favourites = json.loads(read_file(var.fav_path))
    if not favourites:
        if xbmcvfs.exists(var.fav_old_path):
            old_favs = json.loads(read_file(var.fav_old_path))
            if old_favs:
                write_file(var.fav_path, json.dumps(old_favs))
                return old_favs
    return favourites

def write_favourite(title, link):
    if not xbmcvfs.exists(var.profile_path):
        xbmcvfs.mkdirs(var.profile_path)
    items = read_favourites()
    if link not in str(items):
        items.append([title, link])
    write_file(var.fav_path, json.dumps(items))

def get_schedule_and_channels():
    with ThreadPoolExecutor() as executor:
        executor.submit(write_schedule)
        executor.submit(write_channels)

def convert_utc_time_to_local(utc_time_str):
        today = date.today()
        datetime_str = f"{today} {utc_time_str}"
        utc_datetime = datetime.strptime(datetime_str, "%Y-%m-%d %H:%M")
        utc_datetime = utc_datetime.replace(tzinfo=pytz.utc)
        local_tz = get_localzone()
        local_time = utc_datetime.astimezone(local_tz)
        return local_time.strftime("%I:%M %p").lstrip('0')

def get_match_links(match):
    item = {}
    schedule = read_cat_schedule()
    for event in schedule:
        if event['event'] == match:
            item = event
            break
    links = []
    channels = item.get('channels', [])
    for channel in channels:
        if isinstance(channel, dict):
            links.append(
                [
                    channel.get('channel_name'),
                    f"/stream/stream-{channel.get('channel_id')}.php"
                ]
            )
    channels2 = item.get('channels2')
    for channel in channels2:
        if isinstance(channel, dict):
            links.append(
                [
                    channel.get('channel_name'),
                    f"/stream/bet.php?id=bet{channel.get('channel_id')}"
                ]
            )
    return links

def get_search_results():
    stopwords = (
        "a", "an", "and", "are", "as", "at", "be", "but", "by",
        "for", "if", "in", "into", "is", "it", "no", "not", "of",
        "on", "or", "such", "that", "the", "their", "then", "there",
        "these", "they", "this", "to", "was", "will", "with"
    )
    query = xbmcgui.Dialog().input('Enter search query:')
    if not query:
        var.system_exit()
    keywords = [k.lower() for k in query.split(' ') if k not in stopwords]

    schedule = read_schedule()
    
    results = {}
    events = results['events'] = []
    channels = results['channels'] = []
    for key in schedule.keys():
        for cat in schedule[key].keys():
            for event in schedule[key][cat]:
                for keyword in keywords:
                    if keyword.lower() in event['event'].lower() and event not in events:
                        events.append(event)
    
    ch_list = read_channels()
    for keyword in keywords:
        for channel in ch_list:
            if keyword.lower() in channel['title'].lower() and channel not in channels:
                channels.append(channel)
    return results

def gather_streams(url):
    php = url.split('/')[-1]
    allowed = ['stream', 'cast', 'watch', 'plus', 'casting', 'player']
    players = [[f'Link {index+1}', f'{var.base_url}/{a_type}/{php}'] for index, a_type in enumerate(allowed)]
    if var.get_setting_bool('autoplay') is True:
        return players[0][1]
    link = get_multilink(players)
    if not link:
        var.system_exit()
    return link

def resolve_link(url):
    m3u8 = None
    try:
        url = gather_streams(url)
        response = get(url)
        soup = get_soup(response.text)
        iframe = soup.select_one("iframe#thatframe, iframe.video")
        
        url2 = iframe['src']
        
        if 'wikisport' in url2 or 'lovecdn' in url2:
            response = get(url2, url, timeout=60)
            soup = get_soup(response.text)
            url2 = soup.find('iframe')['src']
        
        if 'lovecdn' in url2:
            m3u8 = url2.replace('embed.html', 'index.fmp4.m3u8')
            referer = f'https://{urlparse(url2).netloc}'
            m3u8 = f'{m3u8}|Referer={url2}&Connection=Keep-Alive&User-Agent={var.user_agent}'
            return m3u8
        
        response = get(url2)
        
        if channel_key := re.search(r'const\s+CHANNEL_KEY\s*=\s*"([^"]+)"', response.text):
            channel_key = channel_key.group(1)
            #bundle = re.search(r'const\s+[A-Z]{4}\s*=\s*"([^"]+)"', response.text).group(1)
            bundle = re.search(r'const\s+[A-Z]+\s*=\s*"([^"]+)"', response.text).group(1)
            parts = json.loads(base64.b64decode(bundle).decode("utf-8"))
            for k, v in parts.items():
                parts[k] = base64.b64decode(v).decode("utf-8")
            bx = [40, 60, 61, 33, 103, 57, 33, 57]
            sc = ''.join(chr(b ^ 73) for b in bx)
            host = "https://top2new.newkso.ru/"
            auth_url = (
                f'{host}{sc}'
                f'?channel_id={quote_plus(channel_key)}&'
                f'ts={quote_plus(parts["b_ts"])}&'
                f'rnd={quote_plus(parts["b_rnd"])}&'
                f'sig={quote_plus(parts["b_sig"])}'
            )
            get(auth_url, referer=url2)
                
            server_lookup_url = f"https://{urlparse(url2).netloc}/server_lookup.php?channel_id={channel_key}"
            response = get(server_lookup_url, referer=url2).json()
            server_key = response['server_key']
            if server_key == "top1/cdn":
                m3u8 = f"https://top1.newkso.ru/top1/cdn/{channel_key}/mono.m3u8"
            else:
                m3u8 = f"https://{server_key}new.newkso.ru/{server_key}/{channel_key}/mono.m3u8"
            
            referer = f'https://{urlparse(url2).netloc}'
            m3u8 = f'{m3u8}|Referer={referer}/&Origin={referer}&Connection=Keep-Alive&User-Agent={var.user_agent}'
        
        elif match := re.search(r"atob\('([^']+)'\)", response.text):
            b64_str = match.group(1)
            decoded = base64.b64decode(b64_str).decode('utf-8', errors='ignore')
            init_url = re.search(r'initUrl\s*=\s*"([^"]+)"', decoded)
            if init_url:
                m3u8 = init_url.group(1)
                r_m3u8 = get(m3u8)
                m3u8 = base64.b64decode(r_m3u8.text).decode("utf-8")
                referer = f'https://{urlparse(url2).netloc}'
                m3u8 = f'{m3u8}|Referer={url2}&Connection=Keep-Alive&User-Agent={var.user_agent}'
        
        
        elif 'blogspot.com' in url2:
            channel_id = dict(parse_qsl(urlparse(url2).query)).get('id')
            pattern = rf'"{re.escape(channel_id)}"\s*:\s*\{{[^}}]*?url:\s*"([^"]+)"'
            match = re.search(pattern, response.text, re.DOTALL)
            if match:
                m3u8 = match.group(1)
                referer = f'https://{urlparse(url2).netloc}'
                m3u8 = f'{m3u8}|Referer={referer}/&Origin={referer}&Connection=Keep-Alive&User-Agent={var.user_agent}'
        
        elif match := re.search(r"var\s+PlayS\s*=\s*'([^']+)'", response.text):
            m3u8 = match.group(1)
            referer = f'https://{urlparse(url2).netloc}'
            m3u8 = f'{m3u8}|Referer={referer}/&Origin={referer}&Connection=Keep-Alive&User-Agent={var.user_agent}'
            
    except Exception:
        ok_dialog(f'Error loading stream:\n{traceback.format_exc()}')
        log(f'Error loading stream:\n{traceback.format_exc()}')
        var.system_exit()
    return m3u8
    
    