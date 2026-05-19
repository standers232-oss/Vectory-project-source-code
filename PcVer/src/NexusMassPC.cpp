#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <winhttp.h>
#include <commctrl.h>
#include <string>
#include <vector>
#include <regex>
#include <sstream>
#include <iomanip>
#include <ctime>
#include <algorithm>

#pragma comment(lib, "winhttp.lib")

using std::string;
using std::wstring;

const wchar_t* APP_TITLE = L"Nexus Mass PC";
const string API_KEY = "AIzaSyB4hBCbmicPOm-soqfHAuLGFTV3jXc2ndY";
const string PROJECT_ID = "massangetr";
const string HOST_AUTH = "identitytoolkit.googleapis.com";
const string HOST_FIRESTORE = "firestore.googleapis.com";

struct Profile { string uid, username, nick, avatarUrl; };
struct Chat { string id, otherUid, otherUsername, otherNick, otherAvatarUrl, lastMessage, lastSenderUid, updatedAt; };
struct Message { string text, senderUid, senderUsername, senderNick, createdAt; };

HINSTANCE gInst;
HWND gMain, gLeft, gRight, gStatus;
Profile me;
string idToken;
string currentChatId;
Profile currentOther;
UINT_PTR pollTimer = 0;
size_t lastChatSignature = 0;

wstring utf8ToWide(const string& s) {
    if (s.empty()) return L"";
    int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), NULL, 0);
    wstring w(n, 0);
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], n);
    return w;
}
string wideToUtf8(const wstring& w) {
    if (w.empty()) return "";
    int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), NULL, 0, NULL, NULL);
    string s(n, 0);
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), &s[0], n, NULL, NULL);
    return s;
}
string getText(HWND h) {
    if (!h) return "";
    int len = GetWindowTextLengthW(h);
    wstring w(len + 1, 0);
    GetWindowTextW(h, &w[0], len + 1);
    w.resize(len);
    return wideToUtf8(w);
}
void setText(HWND h, const wstring& w) { SetWindowTextW(h, w.c_str()); }

string urlEncode(const string& s) {
    std::ostringstream out;
    out << std::hex << std::uppercase;
    for (unsigned char c : s) {
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c=='-' || c=='_' || c=='.' || c=='~') out << c;
        else out << '%' << std::setw(2) << std::setfill('0') << (int)c;
    }
    return out.str();
}
string jsonEscape(const string& s) {
    string o;
    for (unsigned char c: s) {
        switch(c) {
            case '\\': o += "\\\\"; break;
            case '"': o += "\\\""; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default: if (c < 32) { char b[8]; sprintf(b, "\\u%04x", c); o += b; } else o += c;
        }
    }
    return o;
}
string jsonUnescape(string s) {
    string o;
    for (size_t i=0;i<s.size();++i) {
        if (s[i]=='\\' && i+1<s.size()) {
            char n=s[++i];
            if(n=='n') o+='\n'; else if(n=='r') o+='\r'; else if(n=='t') o+='\t'; else if(n=='"') o+='"'; else if(n=='\\') o+='\\'; else o+=n;
        } else o += s[i];
    }
    return o;
}
string nowIso() {
    std::time_t t = std::time(nullptr);
    std::tm gm{};
    gmtime_s(&gm, &t);
    char buf[40];
    strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%SZ", &gm);
    return buf;
}

string request(const string& host, const string& path, const string& method, const string& body = "", const string& bearer = "") {
    HINTERNET hSession = WinHttpOpen(L"NexusMassPC/1.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!hSession) return "";
    wstring whost = utf8ToWide(host), wpath = utf8ToWide(path), wmethod = utf8ToWide(method);
    HINTERNET hConnect = WinHttpConnect(hSession, whost.c_str(), INTERNET_DEFAULT_HTTPS_PORT, 0);
    HINTERNET hReq = hConnect ? WinHttpOpenRequest(hConnect, wmethod.c_str(), wpath.c_str(), NULL, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE) : NULL;
    string headers = "Content-Type: application/json\r\n";
    if (!bearer.empty()) headers += "Authorization: Bearer " + bearer + "\r\n";
    wstring wheaders = utf8ToWide(headers);
    BOOL ok = FALSE;
    if (hReq) ok = WinHttpSendRequest(hReq, wheaders.c_str(), (DWORD)-1, body.empty()?WINHTTP_NO_REQUEST_DATA:(LPVOID)body.data(), (DWORD)body.size(), (DWORD)body.size(), 0);
    if (ok) ok = WinHttpReceiveResponse(hReq, NULL);
    string response;
    if (ok) {
        DWORD size = 0;
        do {
            if (!WinHttpQueryDataAvailable(hReq, &size) || !size) break;
            string chunk(size, 0);
            DWORD read = 0;
            if (!WinHttpReadData(hReq, &chunk[0], size, &read)) break;
            chunk.resize(read);
            response += chunk;
        } while (size > 0);
    }
    if (hReq) WinHttpCloseHandle(hReq);
    if (hConnect) WinHttpCloseHandle(hConnect);
    WinHttpCloseHandle(hSession);
    return response;
}

string extractJsonString(const string& json, const string& key) {
    std::regex r("\\\"" + key + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
    std::smatch m;
    if (std::regex_search(json, m, r)) return jsonUnescape(m[1].str());
    return "";
}
string extractFieldString(const string& block, const string& field) {
    std::regex r("\\\"" + field + "\\\"\\s*:\\s*\\{\\s*\\\"stringValue\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
    std::smatch m;
    if (std::regex_search(block, m, r)) return jsonUnescape(m[1].str());
    return "";
}
string extractFieldTimestamp(const string& block, const string& field) {
    std::regex r("\\\"" + field + "\\\"\\s*:\\s*\\{\\s*\\\"timestampValue\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
    std::smatch m;
    if (std::regex_search(block, m, r)) return jsonUnescape(m[1].str());
    return "";
}
string pathBase() { return "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents"; }
string fieldStr(const string& v) { return "{\"stringValue\":\"" + jsonEscape(v) + "\"}"; }
string fieldTs(const string& v) { return "{\"timestampValue\":\"" + jsonEscape(v) + "\"}"; }

bool validUsername(const string& u) {
    if (u.size() < 3 || u.size() > 24) return false;
    for (char c: u) if (!((c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='_'||c=='.')) return false;
    return true;
}
string lowerAscii(string s) { for(char& c:s) if(c>='A'&&c<='Z') c += 32; return s; }
string usernameEmail(const string& u) { return lowerAscii(u) + "@mass.local"; }
string chatIdFor(const string& a, const string& b) { return (a < b) ? a + "_" + b : b + "_" + a; }

void status(const wchar_t* s) { if (gStatus) SetWindowTextW(gStatus, s); }
void clear(HWND h) { if (!h) return; DestroyWindow(h); }
HWND makePanel(int x, int y, int w, int h, HWND parent) {
    return CreateWindowExW(0, L"STATIC", L"", WS_CHILD|WS_VISIBLE, x,y,w,h,parent,NULL,gInst,NULL);
}
HWND label(HWND p, const wchar_t* t, int x,int y,int w,int h, int size=10, bool bold=false) {
    HWND hWnd = CreateWindowW(L"STATIC", t, WS_CHILD|WS_VISIBLE|SS_LEFT, x,y,w,h,p,NULL,gInst,NULL);
    HFONT f = CreateFontW(size*2,0,0,0,bold?FW_BOLD:FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Segoe UI");
    SendMessage(hWnd, WM_SETFONT, (WPARAM)f, TRUE);
    return hWnd;
}
HWND edit(HWND p, const wchar_t* ph, int x,int y,int w,int h, bool pass=false) {
    HWND e = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"", WS_CHILD|WS_VISIBLE|ES_AUTOHSCROLL|(pass?ES_PASSWORD:0), x,y,w,h,p,NULL,gInst,NULL);
    SendMessage(e, EM_SETCUEBANNER, TRUE, (LPARAM)ph);
    HFONT f = CreateFontW(19,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Segoe UI");
    SendMessage(e, WM_SETFONT, (WPARAM)f, TRUE);
    return e;
}
HWND btn(HWND p, const wchar_t* t, int x,int y,int w,int h, int id) {
    HWND b = CreateWindowW(L"BUTTON", t, WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON, x,y,w,h,p,(HMENU)(INT_PTR)id,gInst,NULL);
    HFONT f = CreateFontW(18,0,0,0,FW_BOLD,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Segoe UI");
    SendMessage(b, WM_SETFONT, (WPARAM)f, TRUE);
    return b;
}

bool saveProfile(const Profile& p) {
    string t = nowIso();
    string body = "{\"fields\":{";
    body += "\"uid\":" + fieldStr(p.uid) + ",";
    body += "\"username\":" + fieldStr(p.username) + ",";
    body += "\"usernameLower\":" + fieldStr(lowerAscii(p.username)) + ",";
    body += "\"nick\":" + fieldStr(p.nick) + ",";
    body += "\"avatarUrl\":" + fieldStr(p.avatarUrl) + ",";
    body += "\"updatedAt\":" + fieldTs(t) + "}}";
    string r1 = request(HOST_FIRESTORE, pathBase()+"/users/"+urlEncode(p.uid), "PATCH", body, idToken);
    string r2 = request(HOST_FIRESTORE, pathBase()+"/usernames/"+urlEncode(p.username), "PATCH", body, idToken);
    return r1.find("error") == string::npos && r2.find("error") == string::npos;
}
Profile profileFromDoc(const string& resp, const string& fallbackUsername="") {
    Profile p;
    p.uid = extractFieldString(resp, "uid");
    p.username = extractFieldString(resp, "username");
    p.nick = extractFieldString(resp, "nick");
    p.avatarUrl = extractFieldString(resp, "avatarUrl");
    if (p.username.empty()) p.username = fallbackUsername;
    if (p.nick.empty()) p.nick = p.username;
    return p;
}

bool authRequest(bool reg, const string& username, const string& nick, const string& pass, string& err) {
    string path = string("/v1/accounts:") + (reg ? "signUp" : "signInWithPassword") + "?key=" + API_KEY;
    string body = "{\"email\":\""+jsonEscape(usernameEmail(username))+"\",\"password\":\""+jsonEscape(pass)+"\",\"returnSecureToken\":true}";
    string resp = request(HOST_AUTH, path, "POST", body);
    idToken = extractJsonString(resp, "idToken");
    me.uid = extractJsonString(resp, "localId");
    if (idToken.empty() || me.uid.empty()) { err = extractJsonString(resp, "message"); if(err.empty()) err="Ошибка Firebase Auth"; return false; }
    me.username = lowerAscii(username);
    if (reg) {
        me.nick = nick; me.avatarUrl = "";
        if (!saveProfile(me)) { err = "Аккаунт создан, но профиль не сохранён"; return false; }
    } else {
        string pResp = request(HOST_FIRESTORE, pathBase()+"/users/"+urlEncode(me.uid), "GET", "", idToken);
        Profile loaded = profileFromDoc(pResp, username);
        loaded.uid = me.uid;
        me = loaded;
    }
    return true;
}

std::vector<Chat> loadChats() {
    string body = "{\"structuredQuery\":{\"from\":[{\"collectionId\":\"chats\"}],\"where\":{\"fieldFilter\":{\"field\":{\"fieldPath\":\"members\"},\"op\":\"ARRAY_CONTAINS\",\"value\":"+fieldStr(me.uid)+"}}}}";
    string resp = request(HOST_FIRESTORE, pathBase()+":runQuery", "POST", body, idToken);
    std::vector<Chat> chats;
    size_t pos=0;
    while ((pos = resp.find("\"document\"", pos)) != string::npos) {
        size_t next = resp.find("\"document\"", pos+10);
        string block = resp.substr(pos, next==string::npos ? string::npos : next-pos);
        Chat c;
        string name = extractJsonString(block, "name");
        size_t slash = name.rfind('/'); if (slash != string::npos) c.id = name.substr(slash+1);
        c.lastMessage = extractFieldString(block, "lastMessage");
        c.lastSenderUid = extractFieldString(block, "lastSenderUid");
        c.updatedAt = extractFieldTimestamp(block, "updatedAt");
        // memberInfo parsing by uid markers
        size_t myPos = block.find("\""+me.uid+"\"");
        size_t uidPos = block.find("\"uid\"", myPos==string::npos?0:myPos+me.uid.size());
        // Find the other uid from chat id if possible
        if (!c.id.empty()) {
            size_t sep = c.id.find('_');
            string a = c.id.substr(0, sep), b = sep==string::npos?"":c.id.substr(sep+1);
            c.otherUid = (a == me.uid) ? b : a;
        }
        size_t otherBlockPos = block.find("\""+c.otherUid+"\"");
        string otherBlock = otherBlockPos==string::npos ? block : block.substr(otherBlockPos, 1500);
        c.otherUsername = extractFieldString(otherBlock, "username");
        c.otherNick = extractFieldString(otherBlock, "nick");
        c.otherAvatarUrl = extractFieldString(otherBlock, "avatarUrl");
        if (c.otherNick.empty()) c.otherNick = "Пользователь";
        if (c.otherUsername.empty()) c.otherUsername = "user";
        chats.push_back(c);
        if (next==string::npos) break; else pos = next;
    }
    std::sort(chats.begin(), chats.end(), [](const Chat& a, const Chat& b){ return a.updatedAt > b.updatedAt; });
    return chats;
}

std::vector<Message> loadMessages(const string& cid) {
    string path = pathBase()+"/chats/"+urlEncode(cid)+"/messages?orderBy=createdAt";
    string resp = request(HOST_FIRESTORE, path, "GET", "", idToken);
    std::vector<Message> msgs;
    size_t pos=0;
    while ((pos = resp.find("\"name\"", pos)) != string::npos) {
        size_t next = resp.find("\"name\"", pos+8);
        string block = resp.substr(pos, next==string::npos ? string::npos : next-pos);
        if (block.find("/messages/") == string::npos) { pos += 8; continue; }
        Message m;
        m.text = extractFieldString(block, "text");
        m.senderUid = extractFieldString(block, "senderUid");
        m.senderUsername = extractFieldString(block, "senderUsername");
        m.senderNick = extractFieldString(block, "senderNick");
        m.createdAt = extractFieldTimestamp(block, "createdAt");
        if (!m.text.empty()) msgs.push_back(m);
        if (next==string::npos) break; else pos = next;
    }
    return msgs;
}

bool ensureChat(const Profile& other) {
    currentChatId = chatIdFor(me.uid, other.uid);
    string t = nowIso();
    string body = "{\"fields\":{";
    body += "\"members\":{\"arrayValue\":{\"values\":["+fieldStr(me.uid)+","+fieldStr(other.uid)+"]}},";
    body += "\"updatedAt\":"+fieldTs(t)+",";
    body += "\"memberInfo\":{\"mapValue\":{\"fields\":{";
    body += "\""+jsonEscape(me.uid)+"\":{\"mapValue\":{\"fields\":{\"uid\":"+fieldStr(me.uid)+",\"username\":"+fieldStr(me.username)+",\"nick\":"+fieldStr(me.nick)+",\"avatarUrl\":"+fieldStr(me.avatarUrl)+"}}},";
    body += "\""+jsonEscape(other.uid)+"\":{\"mapValue\":{\"fields\":{\"uid\":"+fieldStr(other.uid)+",\"username\":"+fieldStr(other.username)+",\"nick\":"+fieldStr(other.nick)+",\"avatarUrl\":"+fieldStr(other.avatarUrl)+"}}}";
    body += "}}}}}";
    string resp = request(HOST_FIRESTORE, pathBase()+"/chats/"+urlEncode(currentChatId), "PATCH", body, idToken);
    return resp.find("error") == string::npos;
}

bool sendMessage(const string& text) {
    if (currentChatId.empty()) return false;
    string t = nowIso();
    string msg = "{\"fields\":{\"text\":"+fieldStr(text)+",\"senderUid\":"+fieldStr(me.uid)+",\"senderUsername\":"+fieldStr(me.username)+",\"senderNick\":"+fieldStr(me.nick)+",\"senderAvatarUrl\":"+fieldStr(me.avatarUrl)+",\"createdAt\":"+fieldTs(t)+"}}";
    string resp = request(HOST_FIRESTORE, pathBase()+"/chats/"+urlEncode(currentChatId)+"/messages", "POST", msg, idToken);
    if (resp.find("error") != string::npos) return false;
    string body = "{\"fields\":{";
    body += "\"members\":{\"arrayValue\":{\"values\":["+fieldStr(me.uid)+","+fieldStr(currentOther.uid)+"]}},";
    body += "\"lastMessage\":"+fieldStr(text)+",\"lastSenderUid\":"+fieldStr(me.uid)+",\"updatedAt\":"+fieldTs(t)+",";
    body += "\"memberInfo\":{\"mapValue\":{\"fields\":{";
    body += "\""+jsonEscape(me.uid)+"\":{\"mapValue\":{\"fields\":{\"uid\":"+fieldStr(me.uid)+",\"username\":"+fieldStr(me.username)+",\"nick\":"+fieldStr(me.nick)+",\"avatarUrl\":"+fieldStr(me.avatarUrl)+"}}},";
    body += "\""+jsonEscape(currentOther.uid)+"\":{\"mapValue\":{\"fields\":{\"uid\":"+fieldStr(currentOther.uid)+",\"username\":"+fieldStr(currentOther.username)+",\"nick\":"+fieldStr(currentOther.nick)+",\"avatarUrl\":"+fieldStr(currentOther.avatarUrl)+"}}}";
    body += "}}}}}";
    request(HOST_FIRESTORE, pathBase()+"/chats/"+urlEncode(currentChatId), "PATCH", body, idToken);
    return true;
}

void showMain();
void showLogin(bool reg=false);
void showSearch();
void showSettings();
void openChat(Profile other);

void rebuildLeft() {
    DestroyWindow(gLeft);
    gLeft = CreateWindowW(L"STATIC", L"", WS_CHILD|WS_VISIBLE, 0,0,360,680,gMain,NULL,gInst,NULL);
    label(gLeft, L"Nexus Mass", 24,18,210,34,16,true);
    label(gLeft, utf8ToWide("@"+me.username).c_str(), 24,52,210,24,9,false);
    btn(gLeft, L"🔎 Найти", 230,18,100,38, 201);
    btn(gLeft, L"⚙", 230,60,100,34, 202);
    auto chats = loadChats();
    int y=112;
    if (chats.empty()) label(gLeft, L"Пока нет чатов. Найдите пользователя.", 18,y,320,50,10,false);
    for (size_t i=0;i<chats.size() && i<12;i++) {
        wstring title = utf8ToWide(chats[i].otherNick + "  @" + chats[i].otherUsername);
        wstring last = utf8ToWide(chats[i].lastMessage.empty()?"Открыть чат":chats[i].lastMessage);
        CreateWindowW(L"BUTTON", (title + L"\r\n" + last).c_str(), WS_CHILD|WS_VISIBLE|BS_LEFT|BS_MULTILINE, 14,y,322,62,gLeft,(HMENU)(INT_PTR)(300+i),gInst,NULL);
        y += 70;
    }
}

void showEmptyRight() {
    DestroyWindow(gRight);
    gRight = CreateWindowW(L"STATIC", L"", WS_CHILD|WS_VISIBLE, 360,0,640,680,gMain,NULL,gInst,NULL);
    label(gRight, L"💬", 290,160,100,80,30,true);
    label(gRight, L"Выберите чат", 220,250,240,40,18,true);
    label(gRight, L"Найдите пользователя по юзернейму и начните переписку.", 150,300,410,32,11,false);
    btn(gRight, L"Найти пользователя", 220,350,210,48, 201);
}

void showMain() {
    DestroyWindow(gLeft); DestroyWindow(gRight);
    rebuildLeft(); showEmptyRight();
    status(L"Готово");
}

void showLogin(bool reg) {
    DestroyWindow(gLeft); DestroyWindow(gRight);
    gLeft = NULL;
    gRight = CreateWindowW(L"STATIC", L"", WS_CHILD|WS_VISIBLE, 0,0,1000,680,gMain,NULL,gInst,NULL);
    label(gRight, L"✦", 455,70,90,70,32,true);
    label(gRight, reg?L"Создать аккаунт":L"Добро пожаловать", 360,150,300,42,19,true);
    label(gRight, reg?L"Юзернейм, ник и пароль":L"Вход по юзернейму и паролю", 360,195,320,28,11,false);
    HWND eu = edit(gRight, L"Юзернейм", 350,240,300,36, false); SetWindowLongPtr(eu, GWLP_ID, 101);
    int y=286;
    if (reg) { HWND e = edit(gRight, L"Ник", 350,y,300,36, false); SetWindowLongPtr(e, GWLP_ID, 102); y+=46; }
    HWND ep = edit(gRight, L"Пароль", 350,y,300,36, true); SetWindowLongPtr(ep, GWLP_ID, 103); y+=46;
    if (reg) { HWND er = edit(gRight, L"Повтор пароля", 350,y,300,36, true); SetWindowLongPtr(er, GWLP_ID, 104); y+=52; }
    btn(gRight, reg?L"Зарегистрироваться":L"Войти", 350,y,300,44, reg?112:111); y+=54;
    btn(gRight, reg?L"Уже есть аккаунт? Войти":L"Нет аккаунта? Создать", 350,y,300,36, reg?114:113);
    status(L"Войдите в аккаунт Firebase");
}
HWND findCtl(int id) { return GetDlgItem(gRight, id); }

void doAuth(bool reg) {
    string username = lowerAscii(getText(findCtl(101)));
    string nick = reg ? getText(findCtl(102)) : "";
    string pass = getText(findCtl(103));
    string rep = reg ? getText(findCtl(104)) : "";
    if (!validUsername(username)) { MessageBoxW(gMain,L"Юзернейм: 3–24 символа, латиница/цифры/_/.",APP_TITLE,MB_ICONWARNING); return; }
    if (reg && (nick.size()<2 || nick.size()>64)) { MessageBoxW(gMain,L"Ник должен быть от 2 до 32 символов.",APP_TITLE,MB_ICONWARNING); return; }
    if (pass.size()<6) { MessageBoxW(gMain,L"Пароль минимум 6 символов.",APP_TITLE,MB_ICONWARNING); return; }
    if (reg && pass != rep) { MessageBoxW(gMain,L"Пароли не совпадают.",APP_TITLE,MB_ICONWARNING); return; }
    status(L"Подключаемся к Firebase...");
    string err;
    if (!authRequest(reg, username, nick, pass, err)) {
        MessageBoxW(gMain, utf8ToWide("Ошибка: "+err).c_str(), APP_TITLE, MB_ICONERROR);
        status(L"Ошибка входа"); return;
    }
    showMain();
    SetTimer(gMain, 10, 5000, NULL);
}

void showSearch() {
    DestroyWindow(gRight);
    gRight = CreateWindowW(L"STATIC", L"", WS_CHILD|WS_VISIBLE, 360,0,640,680,gMain,NULL,gInst,NULL);
    label(gRight, L"Поиск пользователя", 32,28,300,38,18,true);
    label(gRight, L"В PC-версии поиск точный: введите полный юзернейм.", 32,72,430,30,10,false);
    HWND e = edit(gRight, L"Юзернейм", 32,116,260,38,false); SetWindowLongPtr(e,GWLP_ID,121);
    btn(gRight, L"Найти", 306,116,120,38,122);
}
void doSearch() {
    string username = lowerAscii(getText(findCtl(121)));
    if (!validUsername(username)) { MessageBoxW(gMain,L"Введите полный юзернейм.",APP_TITLE,MB_ICONWARNING); return; }
    string resp = request(HOST_FIRESTORE, pathBase()+"/usernames/"+urlEncode(username), "GET", "", idToken);
    Profile p = profileFromDoc(resp, username);
    if (p.uid.empty() || p.uid == me.uid) { MessageBoxW(gMain,L"Пользователь не найден.",APP_TITLE,MB_ICONINFORMATION); return; }
    DestroyWindow(gRight);
    gRight = CreateWindowW(L"STATIC", L"", WS_CHILD|WS_VISIBLE, 360,0,640,680,gMain,NULL,gInst,NULL);
    label(gRight, L"Найден пользователь", 32,28,330,38,18,true);
    label(gRight, utf8ToWide(p.nick + "  @" + p.username).c_str(), 32,90,400,34,14,true);
    label(gRight, utf8ToWide("UID: "+p.uid).c_str(), 32,130,520,28,9,false);
    SetPropA(gRight, "found_uid", (HANDLE)new string(p.uid));
    SetPropA(gRight, "found_username", (HANDLE)new string(p.username));
    SetPropA(gRight, "found_nick", (HANDLE)new string(p.nick));
    SetPropA(gRight, "found_avatar", (HANDLE)new string(p.avatarUrl));
    btn(gRight, L"Написать", 32,178,180,44,123);
}
void openFound() {
    auto uid = (string*)GetPropA(gRight,"found_uid");
    auto un = (string*)GetPropA(gRight,"found_username");
    auto ni = (string*)GetPropA(gRight,"found_nick");
    auto av = (string*)GetPropA(gRight,"found_avatar");
    if (!uid||!un||!ni) return;
    Profile p{*uid,*un,*ni,av?*av:""};
    openChat(p);
}

void showSettings() {
    DestroyWindow(gRight);
    gRight = CreateWindowW(L"STATIC", L"", WS_CHILD|WS_VISIBLE, 360,0,640,680,gMain,NULL,gInst,NULL);
    label(gRight, L"Настройки", 32,28,260,38,18,true);
    label(gRight, utf8ToWide("@"+me.username).c_str(), 32,74,260,30,11,false);
    HWND n = edit(gRight, L"Ник", 32,120,300,38,false); SetWindowTextW(n, utf8ToWide(me.nick).c_str()); SetWindowLongPtr(n,GWLP_ID,131);
    HWND a = edit(gRight, L"Avatar URL", 32,172,470,38,false); SetWindowTextW(a, utf8ToWide(me.avatarUrl).c_str()); SetWindowLongPtr(a,GWLP_ID,132);
    btn(gRight, L"Сохранить профиль", 32,228,220,44,133);
    btn(gRight, L"Выйти", 32,290,150,40,134);
    label(gRight, L"Аватар в PC-версии можно указать ссылкой. Загрузка файла есть в Web/Android.", 32,350,520,60,10,false);
}
void saveSettings() {
    me.nick = getText(findCtl(131));
    me.avatarUrl = getText(findCtl(132));
    if (me.nick.size()<2) { MessageBoxW(gMain,L"Ник слишком короткий.",APP_TITLE,MB_ICONWARNING); return; }
    if (saveProfile(me)) { MessageBoxW(gMain,L"Сохранено.",APP_TITLE,MB_OK); showMain(); }
    else MessageBoxW(gMain,L"Не удалось сохранить профиль.",APP_TITLE,MB_ICONERROR);
}

void openChat(Profile other) {
    currentOther = other;
    ensureChat(other);
    DestroyWindow(gRight);
    gRight = CreateWindowW(L"STATIC", L"", WS_CHILD|WS_VISIBLE, 360,0,640,680,gMain,NULL,gInst,NULL);
    label(gRight, utf8ToWide(other.nick).c_str(), 24,16,360,32,17,true);
    label(gRight, utf8ToWide("@"+other.username).c_str(), 24,50,360,24,10,false);
    HWND messages = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"", WS_CHILD|WS_VISIBLE|ES_MULTILINE|ES_AUTOVSCROLL|ES_READONLY|WS_VSCROLL, 24,88,588,470,gRight,(HMENU)141,gInst,NULL);
    HFONT f = CreateFontW(18,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Segoe UI");
    SendMessage(messages, WM_SETFONT, (WPARAM)f, TRUE);
    HWND input = edit(gRight, L"Сообщение...", 24,574,470,38,false); SetWindowLongPtr(input,GWLP_ID,142);
    btn(gRight, L"➤", 506,574,106,38,143);
    rebuildLeft();
    SetTimer(gMain, 11, 2500, NULL);
    status(L"Чат открыт");
    // first render
    SendMessage(gMain, WM_TIMER, 11, 0);
}
void renderMessages() {
    if (currentChatId.empty() || !gRight) return;
    HWND box = findCtl(141); if (!box) return;
    auto msgs = loadMessages(currentChatId);
    wstring out;
    for (auto& m: msgs) {
        bool mine = m.senderUid == me.uid;
        out += mine ? L"Вы: " : utf8ToWide((m.senderNick.empty()?"Пользователь":m.senderNick) + ": ");
        out += utf8ToWide(m.text) + L"\r\n\r\n";
    }
    SetWindowTextW(box, out.c_str());
    SendMessage(box, EM_SETSEL, -1, -1);
    SendMessage(box, EM_SCROLLCARET, 0, 0);
}
void doSend() {
    string text = getText(findCtl(142));
    if (text.empty()) return;
    if (sendMessage(text)) { SetWindowTextW(findCtl(142), L""); renderMessages(); rebuildLeft(); }
    else MessageBoxW(gMain,L"Сообщение не отправлено.",APP_TITLE,MB_ICONERROR);
}
void pollNotifications() {
    if (me.uid.empty()) return;
    auto chats = loadChats();
    size_t sig = 0;
    for (auto& c: chats) if (c.lastSenderUid != me.uid) sig ^= std::hash<string>{}(c.id + c.lastMessage + c.updatedAt);
    if (lastChatSignature != 0 && sig != lastChatSignature) {
        FlashWindow(gMain, TRUE);
        MessageBeep(MB_ICONINFORMATION);
        status(L"Есть новое сообщение");
    }
    lastChatSignature = sig;
}

LRESULT CALLBACK WndProc(HWND h, UINT msg, WPARAM wp, LPARAM lp) {
    switch(msg) {
    case WM_CREATE:
        gStatus = CreateWindowW(L"STATIC", L"", WS_CHILD|WS_VISIBLE, 0,680,1000,24,h,NULL,gInst,NULL);
        showLogin(false);
        return 0;
    case WM_SIZE: return 0;
    case WM_COMMAND: {
        int id = LOWORD(wp);
        if (id==111) doAuth(false);
        else if (id==112) doAuth(true);
        else if (id==113) showLogin(true);
        else if (id==114) showLogin(false);
        else if (id==201) showSearch();
        else if (id==202) showSettings();
        else if (id==122) doSearch();
        else if (id==123) openFound();
        else if (id==133) saveSettings();
        else if (id==134) { me = {}; idToken.clear(); KillTimer(h,10); KillTimer(h,11); showLogin(false); }
        else if (id==143) doSend();
        else if (id>=300 && id<312) {
            auto chats = loadChats(); size_t idx = id-300; if (idx < chats.size()) {
                Profile p{chats[idx].otherUid, chats[idx].otherUsername, chats[idx].otherNick, chats[idx].otherAvatarUrl}; openChat(p);
            }
        }
        return 0;
    }
    case WM_TIMER:
        if (wp==10) pollNotifications();
        if (wp==11) renderMessages();
        return 0;
    case WM_DESTROY:
        PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(h,msg,wp,lp);
}

int WINAPI wWinMain(HINSTANCE hInst, HINSTANCE, PWSTR, int nCmdShow) {
    gInst = hInst;
    INITCOMMONCONTROLSEX ic{sizeof(ic), ICC_STANDARD_CLASSES}; InitCommonControlsEx(&ic);
    WNDCLASSW wc{};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInst;
    wc.lpszClassName = L"NexusMassPCClass";
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hbrBackground = CreateSolidBrush(RGB(248,250,252));
    RegisterClassW(&wc);
    gMain = CreateWindowExW(0, wc.lpszClassName, APP_TITLE, WS_OVERLAPPED|WS_CAPTION|WS_SYSMENU|WS_MINIMIZEBOX, CW_USEDEFAULT, CW_USEDEFAULT, 1016, 743, NULL, NULL, hInst, NULL);
    ShowWindow(gMain, nCmdShow);
    UpdateWindow(gMain);
    MSG msg;
    while (GetMessageW(&msg, NULL, 0, 0)) { TranslateMessage(&msg); DispatchMessageW(&msg); }
    return 0;
}
