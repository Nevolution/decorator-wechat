/*
 * Copyright (C) 2015 The Nevolution Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oasisfeng.nevo.decorators.wechat;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.O_MR1;

/**
 * Static map for WeChat Emoji markers
 *
 * Created by Oasis on 2018-8-9.
 */
class EmojiMap {

	// Pull Request is welcome. Please describe how to verify the related emoji in the pull request.
	// Proper emoji is not found for lines commented out. If you have good candidate, please let us know.
	// Columns are split by "tab" for visual alignment
	static final String[][] MAP = new String[][] {
			{ "OK",			"OK",			"👌" },
			{ "耶",			"Yeah!",		"✌" },
			{ "嘘",			"Silent",		"🤫" },
			{ "晕",			"Dizzy",		"😲" },
			{ "衰",			"BadLuck",		"😳" },
			{ "色",			"Drool",		"😍" },
			{ "囧",			"Tension",		"☺" },
			{ "鸡",			"Chick",		"🐥" },
			{ "强",			"Thumbs Up",	"👍" },
			{ "弱",			"Weak",			"👎" },
			{ "睡",			"Sleep",		"😴" },
			{ "吐",			"Puke",			"🤢" },
			{ "困",			"Drowsy",		"😪" },
			{ "發",			"Rich",			"🀅" },
			{ "微笑",		"Smile",		"😃" },
			{ "撇嘴",		"Grimace",		"😖" },
			{ "发呆",		"Scowl",		"😳" },
			{ "得意",		"CoolGuy",		"😎" },
			{ "流泪",		"Sob",			"😭" },
			{ "害羞",		"Shy",			"☺" },
			{ "闭嘴",		"Shutup",		"🤐" },
			{ "大哭",		"Cry",			"😣" },
			{ "尴尬",		"Awkward",		"😰" },
			{ "发怒",		"Angry",		"😡" },
			{ "调皮",		"Tongue",		"😜" },
			{ "呲牙",		"Grin",			"😁" },
			{ "惊讶",		"Surprise",		"😱" },
			{ "难过",		"Frown",		"🙁" },
			{ "抓狂",		"Scream",		"😫" },
			{ "偷笑",		"Chuckle",		"😅" },
			{ "愉快",		"Joyful",		"☺" },
			{ "白眼",		"Slight",		"🙄" },
			{ "傲慢",		"Smug",			"😕" },
			{ "惊恐",		"Panic",		"😱" },
			{ "流汗",		"Sweat",		"😓" },
			{ "憨笑",		"Laugh",		"😄" },
			{ "悠闲",		"Loafer",		"😌" },
			{ "奋斗",		"Strive",		"💪" },
			{ "咒骂",		"Scold",		"😤" },
			{ "疑问",		"Doubt",		"❓" },
			{ "骷髅",		"Skull",		"💀" },
			{ "敲打",		"Hammer",		"👊" },
			{ "捂脸",		"Facepalm",		"🤦" },
			{ "奸笑",		"Smirk",		"😏" },
			{ "皱眉",		"Concerned",	"😟" },
			{ "红包",		"Packet",		SDK_INT > O_MR1 ? "🧧"/* Emoji 11+ */: "💰" },
			{ "小狗",		"Pup",			"🐶" },
			{ "再见",		"Bye",			"🙋" },
			{ "擦汗",		"Relief",		"😥" },
			{ "鼓掌",		"Clap",			"👏" },
			{ "坏笑",		"Trick",		"👻" },
			{ "哈欠",		"Yawn",			"😪" },
			{ "鄙视",		"Lookdown",		"😒" },
			{ "委屈",		"Wronged",		"😣" },
			{ "阴险",		"Sly",			"😈" },
			{ "亲亲",		"Kiss",			"😘" },
			{ "菜刀",		"Cleaver",		"🔪" },
			{ "西瓜",		"Melon",		"🍉" },
			{ "啤酒",		"Beer",			"🍺" },
			{ "咖啡",		"Coffee",		"☕" },
			{ "猪头",		"Pig",			"🐷" },
			{ "玫瑰",		"Rose",			"🌹" },
			{ "凋谢",		"Wilt",			"🥀" },
			{ "嘴唇",		"Lip",			"💋" },
			{ "爱心",		"Heart",		"❤" },
			{ "心碎",		"BrokenHeart",	"💔" },
			{ "蛋糕",		"Cake",			"🎂" },
			{ "炸弹",		"Bomb",			"💣" },
			{ "便便",		"Poop",			"💩" },
			{ "月亮",		"Moon",			"🌙" },
			{ "太阳",		"Sun",			"🌞" },
			{ "拥抱",		"Hug",			"🤗" },
			{ "握手",		"Shake",		"🤝" },
			{ "胜利",		"Victory",		"✌" },
			{ "抱拳",		"Salute",		"🙏" },
			{ "拳头",		"Fist",			"✊" },
//			{ "跳跳",		"Waddle",		"" },
//			{ "发抖",		"Tremble",		"" },
			{ "怄火",		"Aaagh!",		"😡" },
//			{ "转圈",		"Twirl",		"" },
			{ "蜡烛",		"Candle",		"🕯️" },
//			{ "勾引",		"Beckon",		""},
//			{ "嘿哈",		"Hey",			"" },
//			{ "机智",		"Smart",		"" },
//			{ "抠鼻",		"DigNose",		"" },
//			{ "可怜",		"Whimper",		"" },
			{ "快哭了",		"Puling",		"😔" },
//			{ "左哼哼",		"Bah！L",		"" },
//			{ "右哼哼",		"Bah！R",		"" },
			{ "破涕为笑",	"Lol",			"😂" },

			// From WeChat for iOS
			{ "强壮",		null,			"💪"},
			{ "鬼魂",		null,			"👻"},

			// From WeChat for PC
			{ "篮球",		"Basketball",	"🏀" },
			{ "乒乓",		"PingPong",		"🏓" },
			{ "饭",			"Rice",			"🍚" },
			{ "瓢虫",		"Ladybug",		"🐞" },
			{ "礼物",		"Gift",			"🎁" },
//			{ "差劲",		"Pinky",		"" },
			{ "爱你",		"Love",			"🤟" },
			{ null,			"NO",			"🙅" },
			{ "爱情",		"InLove",		"💕" },
			{ "飞吻",		"Blowkiss",		"😘" },
			{ "闪电",		"Lightning",	"⚡" },
			{ "刀",			null,			"🔪" },		// Dup of "Cleaver"
			{ "足球",		"Soccer",		"⚽" },
			{ "棒球",		"Baseball",		"⚾" },
			{ "橄榄球",		"Football",		"🏈" },
			{ "钱",			"Money",		"💰" },
			{ "相机",		"Camera",		"📷" },
			{ "干杯",		"Cheers",		"🍻" },
			{ "宝石",		"Gem",			"💎" },
			{ "茶",			"Tea",			"🍵" },
			{ "药丸",		"Pill",			"💊" },
			{ "庆祝",		"Party",		"🎆" },
			{ "火箭",		"Rocket ship",	"🚀" },
	};
}
