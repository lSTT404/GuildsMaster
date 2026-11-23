// For an isolated 1.21.11 loopback server only. Run via scripts/playtest.py.
const assert = require('node:assert/strict');
const minecraft = require('minecraft-protocol');
const port = Number(process.argv[2]);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const clients = [];

function extract(component) {
  if (typeof component === 'string') return component;
  if (!component || typeof component !== 'object') return '';
  if (component.type === 'string') return component.value;
  if (component.type === 'list') return component.value.value.map(extract).join('');
  if (component.type === 'compound') return extract(component.value);
  return ['text', 'extra', 'with', 'translate'].map((key) => extract(component[key])).join('');
}

async function until(test, label, timeout = 20000) {
  const limit = Date.now() + timeout;
  while (Date.now() < limit) {
    const result = test();
    if (result) return result;
    await sleep(40);
  }
  throw Error('Timed out: ' + label);
}

async function connect(username) {
  const bot = minecraft.createClient({host: '127.0.0.1', port, username, version: '1.21.11', auth: 'offline'});
  clients.push(bot);
  bot.messages = [];
  bot.windows = [];
  bot.on('error', (error) => console.error(username, error));
  bot.on('system_chat', (packet) => bot.messages.push(extract(packet.content)));
  bot.on('position', (packet) => {
    bot.write('teleport_confirm', {teleportId: packet.teleportId});
    bot.spawned = true;
  });
  bot.on('open_window', (packet) => {
    bot.pendingWindow = {id: packet.windowId, title: extract(packet.windowTitle)};
  });
  bot.on('window_items', (packet) => {
    if (bot.pendingWindow && bot.pendingWindow.id === packet.windowId) {
      bot.windows.push({...bot.pendingWindow, state: packet.stateId, items: packet.items});
      bot.pendingWindow = null;
    }
  });
  await until(() => bot.spawned, username + ' join');
  return bot;
}

async function pace(bot) {
  const wait = Math.max(0, (bot.lastCommand || 0) + 750 - Date.now());
  if (wait) await sleep(wait);
  bot.lastCommand = Date.now();
}

async function response(bot, command, pattern) {
  if (command) await pace(bot);
  const offset = bot.messages.length;
  if (command) bot.chat(command);
  try {
    return await until(() => bot.messages.slice(offset).find((text) => pattern.test(text)), bot.username + ' ' + command);
  } catch (error) {
    console.error(bot.username, bot.messages);
    throw error;
  }
}

async function window(bot, command, title) {
  if (command) await pace(bot);
  const offset = bot.windows.length;
  if (command) bot.chat(command);
  return until(() => bot.windows.slice(offset).find((menu) => title.test(menu.title)), bot.username + ' menu ' + command);
}

function click(bot, menu, slot, mouseButton = 0) {
  bot.write('window_click', {windowId: menu.id, stateId: menu.state, slot, mouseButton, mode: 0, changedSlots: [], cursorItem: undefined});
}

async function main() {
  const ada = await connect('AdaBot');
  const sam = await connect('SamBot');
  const eva = await connect('EvaBot');
  const rio = await connect('RioBot');
  const jo = await connect('JoBot');
  const decline = await connect('DeclineBot');
  const unknown = await connect('UnknownBot');
  const refund = await connect('RefundBot');
  const currency = await window(ada, '/guild create Aurora', /Pay for Aurora/);
  assert(currency.items.length > 12);
  const created = response(ada, null, /Guild Aurora created/);
  click(ada, currency, 10);
  click(ada, currency, 10);
  await created;
  await response(sam, '/guild create Copper tokens', /Guild Copper created/);
  await response(eva, '/guild create Ember crystals', /Guild Ember created/);
  await response(decline, '/guild create Rejected tokens', /provider declined/);
  await response(unknown, '/guild create Unknown tokens', /Payment requires review/);
  await response(refund, '/guild create Refundable tokens', /payment was refunded/);
  await response(ada, '/guild invite RioBot', /Invitation sent/);
  const invites = await window(rio, '/guild invitations', /Invitations/);
  const joined = response(rio, null, /You joined Aurora/);
  click(rio, invites, 10);
  await joined;
  await response(rio, '/guild rename Locked', /rank cannot perform/);
  await response(jo, '/guild apply Aurora', /Application sent/);
  const apps = await window(ada, '/guild applications', /Applications/);
  const approved = response(jo, null, /You joined Aurora/);
  click(ada, apps, 10);
  await approved;
  await response(ada, '/guild role RioBot builder', /Rank updated/);
  await response(rio, '/gmtestmove 7', /PROBE moved/);
  await response(rio, '/guild sethome', /Guild home set/);
  await response(rio, '/gmtestmove 9', /PROBE moved/);
  await response(rio, '/guild home', /Teleporting/);
  await response(rio, '/gmtestmove 10', /PROBE moved/);
  assert(rio.messages.some((message) => /teleport cancelled/i.test(message)));
  await response(rio, '/guild home', /arrived/);
  await response(rio, '/gmteststate', /x=7/);
  await response(ada, '/guild alliance send Copper', /Alliance proposal/);
  await response(sam, '/guild alliance accept Aurora', /Alliance with Aurora accepted/);
  await window(ada, '/guild alliance info Copper', /Alliance details/);
  await response(ada, '/guild war send Ember', /War proposal/);
  await response(eva, '/guild war accept Aurora', /War with Aurora accepted/);
  await response(ada, '/gmtestkill EvaBot', /PROBE attacked/);
  await response(ada, '/guild territory claim', /Chunk claimed/);
  await response(ada, '/guild upgrade', /capacity bonus is now/);
  await response(ada, '/guild light on', /glow enabled/);
  await response(ada, '/gmteststate', /glow=true/);
  await response(ada, '/guild light off', /glow disabled/);
  await response(ada, '/gmteststate', /glow=false/);
  await window(ada, '/guild history', /Guild history/);
  await window(ada, '/guild statistics', /statistics/);
  await window(ada, '/guild top', /Guild ranking/);
  const transfer = await window(ada, '/guild transfer RioBot', /Transfer leadership/);
  const done = response(ada, null, /Leadership transferred/);
  click(ada, transfer, 11);
  await done;
  await response(ada, '/guild rename Locked', /rank cannot perform/);
  await response(rio, '/guild rename AuroraPrime', /Guild renamed/);
  const chat = response(jo, null, /OnlyGuildMembers/);
  await response(rio, '/guild chat OnlyGuildMembers', /OnlyGuildMembers/);
  await chat;
  assert(!sam.messages.some((message) => /OnlyGuildMembers/.test(message)));
  if (process.env.GM_TEST_PAPI === '1') {
    const expected = process.env.GM_TEST_LUCKPERMS === '1' ? 'Veteran' : 'Member';
    await response(jo, '/gmtestpapi', new RegExp('PROBE guild=AuroraPrime role=' + expected + ' members=3'));
  }
  console.log('PASS three paid currencies, decline, unconfirmed charge, refund, duplicate menu clicks, invitations, applications, ranks, transfer, alliance, war, territory, upgrade, home, glow, history, rankings and private chat');
}

async function disabled() {
  const bot = await connect('RioBot');
  const commands = ['/guild invite AdaBot', '/guild applications', '/guild alliance', '/guild chat hi',
    '/guild home', '/guild history', '/guild light on', '/guild upgrade', '/guild top',
    '/guild war', '/guild territory list', '/guild announce hi', '/guild statistics',
    '/guild role AdaBot member', '/guild level'];
  for (const command of commands) {
    await response(bot, command, /administrator disabled/i);
    await sleep(150);
  }
  console.log('PASS disabled mechanics block their player commands');
}

(process.argv[3] === 'disabled' ? disabled() : main()).then(() => {
  for (const bot of clients) bot.end();
}, (error) => {
  console.error(error);
  for (const bot of clients) bot.end();
  process.exitCode = 1;
});
